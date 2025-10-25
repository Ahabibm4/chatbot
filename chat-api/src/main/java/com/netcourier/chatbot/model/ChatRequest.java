package com.netcourier.chatbot.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ChatRequest(
        @NotBlank String conversationId,
        @NotBlank String tenantId,
        @NotBlank String userId,
        @NotNull List<ChatTurn> turns,
        ChatContext context
) {
}
