package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;

import java.util.Map;

public interface ToolAdapter {
    String name();

    boolean supports(String toolName);

    ToolExecutionResult execute(ChatRequest request, Map<String, Object> slots);
}
