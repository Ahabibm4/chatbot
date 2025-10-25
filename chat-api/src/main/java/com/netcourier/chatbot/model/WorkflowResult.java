package com.netcourier.chatbot.model;

import java.util.Map;
import java.util.Optional;

public record WorkflowResult(
        String workflowId,
        String state,
        Map<String, Object> slots,
        Optional<String> toolToInvoke,
        String responseMessage,
        ToolCallResult toolResult
) {
    public WorkflowResult withToolResult(ToolCallResult toolResult) {
        return new WorkflowResult(workflowId, state, slots, toolToInvoke, responseMessage, toolResult);
    }

    public WorkflowResult withResponse(String response) {
        return new WorkflowResult(workflowId, state, slots, toolToInvoke, response, toolResult);
    }
}
