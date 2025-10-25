package com.netcourier.chatbot.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatSubmissionContext(
        @NotBlank String tenantId,
        @NotBlank String userId,
        @NotBlank String ui,
        @NotBlank String locale,
        List<String> roles
) {
}
