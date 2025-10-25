package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ToolCallResult;

public record ToolExecutionResult(String toolName, boolean success, String detail) {

    public ToolCallResult toModel() {
        return success ? ToolCallResult.success(toolName, detail) : ToolCallResult.failure(toolName, detail);
    }
}
