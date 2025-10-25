package com.netcourier.chatbot.model;

public record RetrievedChunk(
        String docId,
        String title,
        int page,
        String text,
        double score,
        String source
) {
}
