package com.netcourier.chatbot.service.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatClient;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
        List<OpenAiChatClient.Message> messages = buildMessages(request);

        try {
            OpenAiChatClient.ChatCompletionResponse response = chatClient.complete(
                    new OpenAiChatClient.Request(model, messages, temperature, maxOutputTokens, Map.of())
            );
            OpenAiChatClient.Choice choice = response.firstChoice();
            if (choice == null || choice.message() == null || choice.message().content() == null) {
                log.warn("LLM response was empty for classification {}", request.classification());
                return fallbackResponse();
            }
            String content = choice.message().content().trim();
            String guardrail = mapGuardrail(choice.finishReason());
            return new LlmResponse(content, guardrail);
        } catch (OpenAiChatException ex) {
            log.error("LLM invocation failed: {}", ex.getMessage());
            return fallbackResponse();
        } catch (Exception ex) {
            log.error("Unexpected error while invoking LLM", ex);
            return fallbackResponse();
        }
    }

    private LlmResponse fallbackResponse() {
        String message = "I'm having trouble reaching the AI assistant right now. Please try again in a moment or contact a NetCourier agent.";
        return new LlmResponse(message, "ERROR");
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
}
