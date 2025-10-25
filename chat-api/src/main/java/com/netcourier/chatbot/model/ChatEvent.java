package com.netcourier.chatbot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
        ChatEventType type,
        String text,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata
) {

    public ChatEvent {
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    public static ChatEvent thinking(String text, Map<String, Object> metadata) {
        return new ChatEvent(ChatEventType.THINKING, text, metadata);
    }

    public static ChatEvent partial(String text, Map<String, Object> metadata) {
        return new ChatEvent(ChatEventType.PARTIAL, text, metadata);
    }

    public static ChatEvent toolResult(String text, Map<String, Object> metadata) {
        return new ChatEvent(ChatEventType.TOOL_RESULT, text, metadata);
    }

    public static ChatEvent finalResponse(String text, Map<String, Object> metadata) {
        return new ChatEvent(ChatEventType.FINAL, text, metadata);
    }
}
