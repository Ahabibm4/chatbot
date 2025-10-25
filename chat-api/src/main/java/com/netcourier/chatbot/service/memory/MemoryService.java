package com.netcourier.chatbot.service.memory;

import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.WorkflowResult;

public interface MemoryService {
    void appendTurns(ChatRequest request);

    void storeAssistantMessage(ChatRequest request, ChatMessage message, WorkflowResult workflowResult);
}
