package com.netcourier.chatbot.model;

import java.util.List;

public record RetrievalSummary(
        String intent,
        List<RetrievedChunk> chunks
) {
}
