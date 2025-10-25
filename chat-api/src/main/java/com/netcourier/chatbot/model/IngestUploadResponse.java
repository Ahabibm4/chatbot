package com.netcourier.chatbot.model;

public record IngestUploadResponse(String tenantId, String docId, int chunks) {
}
