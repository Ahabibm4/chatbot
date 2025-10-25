package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final List<ToolAdapter> adapters;

    public ToolRegistry(List<ToolAdapter> adapters) {
        this.adapters = adapters;
    }

    public ToolExecutionResult execute(String toolName, ChatRequest request, Map<String, Object> slots) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(toolName))
                .findFirst()
                .map(adapter -> adapter.execute(request, slots))
                .orElseGet(() -> new ToolExecutionResult(toolName, false, "No adapter configured"));
    }
}
