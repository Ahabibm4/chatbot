package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.RetrievedChunk;

import java.util.List;
import java.util.Map;

public record LlmRequest(String systemPrompt,
                         String userPrompt,
                         List<RetrievedChunk> context,
                         Map<String, Object> workflowContext,
                         String classification) {
}
