package com.netcourier.chatbot.service.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatClient;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final OpenAiChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxOutputTokens;

    public OpenAiLlmClient(OpenAiChatClient chatClient,
                           ObjectMapper objectMapper,
                           @Value("${chat.llm.model:netcourier-llama-3.1}") String model,
                           @Value("${chat.llm.temperature:0.35}") double temperature,
                           @Value("${chat.llm.max-output-tokens:1500}") int maxOutputTokens) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.model = Objects.requireNonNullElse(model, "netcourier-llama-3.1");
        this.temperature = temperature;
        this.maxOutputTokens = Math.max(256, maxOutputTokens);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        try {
            LlmStreamEvent finalEvent = stream(request)
                    .filter(event -> event.type() == LlmStreamEvent.Type.FINAL)
                    .blockFirst();
            if (finalEvent == null || finalEvent.text() == null || finalEvent.text().isBlank()) {
                log.warn("LLM response was empty for classification {}", request.classification());
                return fallbackResponse();
            }
            String guardrail = finalEvent.guardrailAction() == null ? "ALLOW" : finalEvent.guardrailAction();
            return new LlmResponse(finalEvent.text(), guardrail);
        } catch (OpenAiChatException ex) {
            log.error("LLM invocation failed: {}", ex.getMessage());
            return fallbackResponse();
        } catch (Exception ex) {
            log.error("Unexpected error while invoking LLM", ex);
            return fallbackResponse();
        }
    }

    @Override
    public Flux<LlmStreamEvent> stream(LlmRequest request) {
        List<OpenAiChatClient.Message> messages = buildMessages(request);
        StringBuilder content = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();

        return chatClient.stream(new OpenAiChatClient.Request(model, messages, temperature, maxOutputTokens, Map.of()))
                .concatMap(event -> {
                    if (event.done()) {
                        String text = content.toString().trim();
                        if (text.isEmpty()) {
                            log.warn("Streaming completed without content for classification {}", request.classification());
                            String fallback = fallbackMessage();
                            content.append(fallback);
                            return Flux.just(LlmStreamEvent.finalEvent(fallback, "ERROR"));
                        }
                        String guardrail = mapGuardrail(finishReason.get());
                        return Flux.just(LlmStreamEvent.finalEvent(text, guardrail));
                    }
                    try {
                        StreamResponse response = objectMapper.readValue(event.data(), StreamResponse.class);
                        StreamChoice choice = response.firstChoice();
                        if (choice == null) {
                            return Flux.empty();
                        }
                        if (choice.finishReason() != null) {
                            finishReason.set(choice.finishReason());
                        }
                        StreamDelta delta = choice.delta();
                        if (delta != null && delta.content() != null && !delta.content().isBlank()) {
                            content.append(delta.content());
                            return Flux.just(LlmStreamEvent.partial(delta.content()));
                        }
                        return Flux.empty();
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse streaming chunk", e);
                        return Flux.empty();
                    }
                })
                .onErrorMap(ex -> ex instanceof OpenAiChatException ? ex : new OpenAiChatException("Failed to stream chat completion", ex))
                .concatWith(Flux.defer(() -> {
                    if (content.length() == 0) {
                        return Flux.just(LlmStreamEvent.finalEvent(fallbackMessage(), "ERROR"));
                    }
                    return Flux.empty();
                }));
    }

    private LlmResponse fallbackResponse() {
        return new LlmResponse(fallbackMessage(), "ERROR");
    }

    private String fallbackMessage() {
        return "I'm having trouble reaching the AI assistant right now. Please try again in a moment or contact a NetCourier agent.";
    }

    private List<OpenAiChatClient.Message> buildMessages(LlmRequest request) {
        List<OpenAiChatClient.Message> messages = new ArrayList<>();
        messages.add(new OpenAiChatClient.Message("system", request.systemPrompt()));

        StringBuilder builder = new StringBuilder();
        builder.append("Conversation classification: ")
                .append(request.classification() == null ? "UNKNOWN" : request.classification())
                .append('\n');

        if (request.workflowContext() != null && !request.workflowContext().isEmpty()) {
            builder.append("Workflow context: ")
                    .append(toJson(request.workflowContext()))
                    .append('\n');
        }

        if (request.context() != null && !request.context().isEmpty()) {
            builder.append("Retrieved knowledge:\n")
                    .append(renderContext(request.context()))
                    .append('\n');
        } else {
            builder.append("No retrieval context was available for this turn.\n");
        }

        builder.append("User request:\n")
                .append(request.userPrompt() == null ? "" : request.userPrompt().trim())
                .append("\n\nRespond with factual guidance. If you reference retrieved knowledge, cite it using the format [Title · p.X].")
                .append(" Provide a concise summary at the end under the heading 'Summary'.");

        messages.add(new OpenAiChatClient.Message("user", builder.toString()));
        return List.copyOf(messages);
    }

    private String renderContext(List<RetrievedChunk> context) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < context.size(); i++) {
            RetrievedChunk chunk = context.get(i);
            builder.append(i + 1)
                    .append('.').append(' ')
                    .append(normalise(chunk.text()))
                    .append(" (reference: ")
                    .append(formatReference(chunk))
                    .append(")\n");
        }
        return builder.toString();
    }

    private String normalise(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= 600) {
            return trimmed;
        }
        return trimmed.substring(0, 597) + "...";
    }

    private String formatReference(RetrievedChunk chunk) {
        String title = chunk.title() == null || chunk.title().isBlank() ? "Document" : chunk.title();
        return String.format(Locale.ROOT, "%s · p.%d", title, chunk.page());
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize workflow context", e);
            return data.toString();
        }
    }

    private String mapGuardrail(String finishReason) {
        if (finishReason == null) {
            return "ALLOW";
        }
        return switch (finishReason) {
            case "stop" -> "ALLOW";
            case "length" -> "TRUNCATED";
            case "content_filter" -> "BLOCKED";
            default -> finishReason.toUpperCase(Locale.ROOT);
        };
    }

    private record StreamResponse(List<StreamChoice> choices) {

        private StreamChoice firstChoice() {
            return choices == null || choices.isEmpty() ? null : choices.getFirst();
        }
    }

    private record StreamChoice(StreamDelta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    private record StreamDelta(@JsonProperty("content") String content) {
    }
}
