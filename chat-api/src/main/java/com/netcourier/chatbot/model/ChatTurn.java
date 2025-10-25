package com.netcourier.chatbot.model;

import jakarta.validation.constraints.NotNull;

public record ChatTurn(
        @NotNull ChatMessageRole role,
        String content
) {
}
