package com.netcourier.chatbot.model;

public record ToolCallResult(
        String toolName,
        String status,
        String detail
) {
    public static ToolCallResult success(String toolName, String detail) {
        return new ToolCallResult(toolName, "SUCCESS", detail);
    }

    public static ToolCallResult failure(String toolName, String detail) {
        return new ToolCallResult(toolName, "FAILED", detail);
    }
}
