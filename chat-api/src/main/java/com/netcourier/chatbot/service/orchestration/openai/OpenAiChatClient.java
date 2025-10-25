package com.netcourier.chatbot.service.orchestration.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private final WebClient webClient;
    private final Duration timeout;

    public OpenAiChatClient(@Qualifier("llmWebClient") WebClient webClient,
                            @Value("${chat.llm.timeout-seconds:60}") long timeoutSeconds) {
        this.webClient = webClient;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public ChatCompletionResponse complete(Request request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", request.model());
        payload.put("messages", request.messages());
        payload.put("stream", Boolean.FALSE);
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            payload.put("max_tokens", request.maxTokens());
        }
        if (request.extraParams() != null && !request.extraParams().isEmpty()) {
            payload.putAll(request.extraParams());
        }

        try {
            return webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(ChatCompletionResponse.class)
                    .timeout(timeout)
                    .onErrorResume(WebClientResponseException.class, this::logAndWrap)
                    .block(timeout);
        } catch (Exception ex) {
            if (!(ex instanceof OpenAiChatException)) {
                log.warn("LLM chat completion failed: {}", ex.getMessage(), ex);
            }
            throw ex instanceof OpenAiChatException openAiChatException
                    ? openAiChatException
                    : new OpenAiChatException("Failed to invoke chat completion", ex);
        }
    }

    private Mono<ChatCompletionResponse> logAndWrap(WebClientResponseException exception) {
        HttpStatus status = exception.getStatusCode();
        log.warn("LLM chat completion returned {}: {}", status, exception.getResponseBodyAsString());
        return Mono.error(new OpenAiChatException("Chat completion returned " + status.value(), exception));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String model,
                          List<Message> messages,
                          Double temperature,
                          Integer maxTokens,
                          Map<String, Object> extraParams) {
    }

    public record Message(String role, String content) {
    }

    public record ChatCompletionResponse(List<Choice> choices, Usage usage) {

        public Choice firstChoice() {
            return choices == null || choices.isEmpty() ? null : choices.getFirst();
        }
    }

    public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    public record Usage(@JsonProperty("total_tokens") int totalTokens,
                        @JsonProperty("prompt_tokens") int promptTokens,
                        @JsonProperty("completion_tokens") int completionTokens) {
    }
}
