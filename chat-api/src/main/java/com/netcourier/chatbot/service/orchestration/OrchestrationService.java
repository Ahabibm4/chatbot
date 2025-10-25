package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.WorkflowResult;

import java.util.List;

public interface OrchestrationService {

    GuardedResponse orchestrate(ChatRequest request,
                                String intent,
                                List<RetrievedChunk> chunks,
                                WorkflowResult workflowResult);
}
