package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.WorkflowResult;

import java.util.List;

public class QueryClassifier {

    public QueryType classify(ChatRequest request, String intent, List<RetrievedChunk> chunks, WorkflowResult workflowResult) {
        if (workflowResult.toolToInvoke().isPresent()) {
            return QueryType.TOOL;
        }
        if (chunks != null && !chunks.isEmpty()) {
            return QueryType.RAG;
        }
        if (intent != null && intent.toLowerCase().contains("faq")) {
            return QueryType.FAQ;
        }
        return QueryType.CHAT;
    }

    public enum QueryType {
        FAQ,
        RAG,
        TOOL,
        CHAT
    }
}
