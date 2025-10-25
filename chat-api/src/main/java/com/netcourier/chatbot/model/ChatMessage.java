package com.netcourier.chatbot.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatMessage(
        UUID id,
        ChatMessageRole role,
        String content,
        OffsetDateTime createdAt,
        boolean streaming
) {
}
