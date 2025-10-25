package com.netcourier.chatbot.service.workflow;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.WorkflowResult;

public interface WorkflowEngine {
    WorkflowResult handle(ChatRequest request, String intent);
}
