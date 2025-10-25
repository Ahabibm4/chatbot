package com.netcourier.chatbot.model;

import java.util.Map;

public record WorkflowSummary(
        String workflowId,
        String state,
        Map<String, Object> slots,
        ToolCallResult toolResult
) {
}
