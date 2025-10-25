package com.netcourier.chatbot.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatSubmission(
        @NotBlank String sessionId,
        @NotBlank String message,
        @NotNull @Valid ChatSubmissionContext context
) {
}
