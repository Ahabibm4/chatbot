package com.netcourier.chatbot.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record IngestTextRequest(@NotBlank String tenantId,
                                String title,
                                @NotBlank String text,
                                List<String> roles,
                                String externalId,
                                String author,
                                String source,
                                String createdAtIso) {
}
