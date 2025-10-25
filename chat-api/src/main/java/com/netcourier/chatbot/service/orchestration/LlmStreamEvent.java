package com.netcourier.chatbot.service.orchestration;

import java.util.Objects;

public record LlmStreamEvent(Type type, String text, String guardrailAction) {

    public enum Type {
        PARTIAL,
        FINAL
    }

    public static LlmStreamEvent partial(String text) {
        Objects.requireNonNull(text, "text");
        return new LlmStreamEvent(Type.PARTIAL, text, null);
    }

    public static LlmStreamEvent finalEvent(String text, String guardrailAction) {
        return new LlmStreamEvent(Type.FINAL, text, guardrailAction);
    }
}
