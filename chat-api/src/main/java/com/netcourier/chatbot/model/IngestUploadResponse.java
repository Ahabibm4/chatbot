package com.netcourier.chatbot.model;

import com.netcourier.chatbot.service.ingestion.DocumentMetadata;

public record IngestUploadResponse(String tenantId,
                                   String docId,
                                   int chunks,
                                   int version,
                                   boolean deduplicated,
                                   DocumentMetadata metadata) {
}
