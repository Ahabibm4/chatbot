package com.netcourier.chatbot.model;

import java.util.List;

public record ChatResponse(
        String conversationId,
        String tenantId,
        List<ChatMessage> messages,
        RetrievalSummary retrieval,
        WorkflowSummary workflow,
        List<Citation> citations,
        String guardrailAction
) {
}
