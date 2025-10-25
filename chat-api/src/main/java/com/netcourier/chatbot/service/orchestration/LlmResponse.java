package com.netcourier.chatbot.service.orchestration;

public record LlmResponse(String answer, String guardrailAction) {
    public static LlmResponse allow(String answer) {
        return new LlmResponse(answer, "ALLOW");
    }

    public static LlmResponse blocked(String answer) {
        return new LlmResponse(answer, "BLOCKED");
    }
}
