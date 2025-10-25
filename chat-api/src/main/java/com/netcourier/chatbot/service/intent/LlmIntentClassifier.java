package com.netcourier.chatbot.service.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatClient;
import com.netcourier.chatbot.service.orchestration.openai.OpenAiChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    private final OpenAiChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public LlmIntentClassifier(OpenAiChatClient chatClient,
                               ObjectMapper objectMapper,
                               @Value("${chat.intent.llm.model:${chat.llm.model:netcourier-llama-3.1}}") String model,
                               @Value("${chat.intent.llm.temperature:0.0}") double temperature,
                               @Value("${chat.intent.llm.max-output-tokens:512}") int maxTokens) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.model = Objects.requireNonNullElse(model, "netcourier-llama-3.1");
        this.temperature = temperature;
        this.maxTokens = Math.max(128, maxTokens);
    }

    public Optional<Classification> classify(ChatRequest request, List<String> candidateIntents) {
        if (candidateIntents == null || candidateIntents.isEmpty()) {
            return Optional.empty();
        }

        List<OpenAiChatClient.Message> messages = buildMessages(request, candidateIntents);
        Map<String, Object> params = Map.of(
                "response_format", Map.of("type", "json_object")
        );

        try {
            OpenAiChatClient.ChatCompletionResponse response = chatClient.complete(
                    new OpenAiChatClient.Request(model, messages, temperature, maxTokens, params)
            );
            OpenAiChatClient.Choice choice = response.firstChoice();
            if (choice == null || choice.message() == null || choice.message().content() == null) {
                return Optional.empty();
            }
            String content = sanitize(choice.message().content());
            JsonNode node = objectMapper.readTree(content);
            String intent = node.path("intent").asText(null);
            double confidence = node.path("confidence").asDouble(0.0);
            String reason = node.path("reason").asText(null);
            if (intent == null || !candidateIntents.contains(intent)) {
                return Optional.empty();
            }
            return Optional.of(new Classification(intent, confidence, reason));
        } catch (OpenAiChatException ex) {
            log.warn("LLM intent classification failed with API error: {}", ex.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("LLM intent classification parsing failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private List<OpenAiChatClient.Message> buildMessages(ChatRequest request, List<String> candidateIntents) {
        List<OpenAiChatClient.Message> messages = new ArrayList<>();
        String system = "You are an intent classifier for the NetCourier assistant. Return a strict JSON object with keys intent, confidence, and reason." +
                " The intent value MUST be one of: " + String.join(", ", candidateIntents) +
                ". Confidence must be between 0.0 and 1.0. Use the fallback intent when unsure.";
        messages.add(new OpenAiChatClient.Message("system", system));

        StringBuilder builder = new StringBuilder();
        builder.append("Tenant: ").append(request.tenantId()).append('\n');
        if (request.context() != null) {
            builder.append("UI Surface: ")
                    .append(request.context().ui())
                    .append('\n');
            Set<String> roles = request.context().roles();
            if (roles != null && !roles.isEmpty()) {
                builder.append("Roles: ")
                        .append(String.join(", ", roles))
                        .append('\n');
            }
        }
        builder.append("Conversation:\n");
        for (ChatTurn turn : request.turns()) {
            builder.append(turn.role().name())
                    .append(':')
                    .append(' ')
                    .append(turn.content() == null ? "" : turn.content().trim())
                    .append('\n');
        }

        messages.add(new OpenAiChatClient.Message("user", builder.toString()));
        return List.copyOf(messages);
    }

    private String sanitize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed = trimmed.replaceAll("^```json\\s*", "");
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    public record Classification(String intent, double confidence, String reason) {
    }
}
