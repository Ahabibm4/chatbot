package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatContext;
import com.netcourier.chatbot.model.ChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ToolRegistry {

    private final List<ToolAdapter> adapters;
    private final ToolAuditService auditService;
    private final Counter allowedCounter;
    private final Counter deniedCounter;

    public ToolRegistry(List<ToolAdapter> adapters,
                        ToolAuditService auditService,
                        MeterRegistry meterRegistry) {
        this.adapters = adapters;
        this.auditService = auditService;
        this.allowedCounter = meterRegistry.counter("chat.tools.invocations", "outcome", "allowed");
        this.deniedCounter = meterRegistry.counter("chat.tools.invocations", "outcome", "denied");
    }

    public ToolExecutionResult execute(String toolName, ChatRequest request, Map<String, Object> slots) {
        Optional<ToolAdapter> adapterOptional = adapters.stream()
                .filter(adapter -> adapter.supports(toolName))
                .findFirst();
        if (adapterOptional.isEmpty()) {
            deniedCounter.increment();
            auditService.denied(toolName, request, "No adapter configured");
            return new ToolExecutionResult(toolName, false, "No adapter configured");
        }
        ToolAdapter adapter = adapterOptional.get();
        ToolSpecification specification = adapter.specification();
        Set<String> roles = resolveRoles(request.context());
        if (!specification.isAuthorized(roles)) {
            deniedCounter.increment();
            auditService.denied(toolName, request, "Caller lacks required role");
            return new ToolExecutionResult(toolName, false, "Caller is not authorized for tool " + toolName);
        }
        ToolSpecification.ValidationResult validationResult = specification.validate(slots);
        if (!validationResult.valid()) {
            deniedCounter.increment();
            auditService.denied(toolName, request, validationResult.message());
            return new ToolExecutionResult(toolName, false, validationResult.message());
        }
        ToolExecutionResult result = adapter.execute(request, validationResult.sanitized());
        auditService.record(toolName, request, validationResult.sanitized(), result, specification.audit());
        allowedCounter.increment();
        return result;
    }

    private Set<String> resolveRoles(ChatContext context) {
        return context == null || context.roles() == null ? Set.of() : context.roles();
    }
}
