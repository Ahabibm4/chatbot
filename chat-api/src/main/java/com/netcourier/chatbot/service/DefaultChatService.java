package com.netcourier.chatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.*;
import com.netcourier.chatbot.service.intent.IntentRouter;
import com.netcourier.chatbot.service.memory.MemoryService;
import com.netcourier.chatbot.service.retrieval.RagService;
import com.netcourier.chatbot.service.tools.ToolExecutionResult;
import com.netcourier.chatbot.service.tools.ToolRegistry;
import com.netcourier.chatbot.service.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DefaultChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatService.class);

    private final IntentRouter intentRouter;
    private final RagService ragService;
    private final WorkflowEngine workflowEngine;
    private final MemoryService memoryService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public DefaultChatService(IntentRouter intentRouter,
                              RagService ragService,
                              WorkflowEngine workflowEngine,
                              MemoryService memoryService,
                              ToolRegistry toolRegistry,
                              ObjectMapper objectMapper) {
        this.intentRouter = intentRouter;
        this.ragService = ragService;
        this.workflowEngine = workflowEngine;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        memoryService.appendTurns(request);

        sink.tryEmitNext(jsonLine("status", "processing"));

        var intent = intentRouter.route(request);
        sink.tryEmitNext(jsonLine("intent", intent));

        List<RetrievedChunk> chunks = ragService.retrieve(request, intent);
        sink.tryEmitNext(jsonLine("retrieval", chunks));

        WorkflowResult workflowResult = workflowEngine.handle(request, intent);
        if (workflowResult.toolToInvoke().isPresent()) {
            ToolExecutionResult result = toolRegistry.execute(workflowResult.toolToInvoke().get(), request, workflowResult.slots());
            workflowResult = workflowResult.withToolResult(result.toModel());
            sink.tryEmitNext(jsonLine("tool", result));
        }

        ChatMessage assistantMessage = new ChatMessage(
                UUID.randomUUID(),
                ChatMessageRole.ASSISTANT,
                workflowResult.responseMessage(),
                OffsetDateTime.now(),
                true
        );
        sink.tryEmitNext(jsonLine("message", assistantMessage));

        memoryService.storeAssistantMessage(request, assistantMessage, workflowResult);

        sink.tryEmitNext(jsonLine("status", "completed"));
        sink.tryEmitComplete();
        return sink.asFlux();
    }

    @Override
    public ChatResponse completeChat(ChatRequest request) {
        memoryService.appendTurns(request);
        var intent = intentRouter.route(request);
        List<RetrievedChunk> chunks = ragService.retrieve(request, intent);
        WorkflowResult workflowResult = workflowEngine.handle(request, intent);
        ToolCallResult toolResult = null;
        if (workflowResult.toolToInvoke().isPresent()) {
            ToolExecutionResult result = toolRegistry.execute(workflowResult.toolToInvoke().get(), request, workflowResult.slots());
            workflowResult = workflowResult.withToolResult(result.toModel());
            toolResult = result.toModel();
        }
        ChatMessage assistantMessage = new ChatMessage(
                UUID.randomUUID(),
                ChatMessageRole.ASSISTANT,
                workflowResult.responseMessage(),
                OffsetDateTime.now(),
                false
        );
        memoryService.storeAssistantMessage(request, assistantMessage, workflowResult);

        List<ChatMessage> messages = new ArrayList<>(request.turns().stream()
                .map(turn -> new ChatMessage(UUID.randomUUID(), turn.role(), turn.content(), OffsetDateTime.now(), false))
                .toList());
        messages.add(assistantMessage);

        return new ChatResponse(
                request.conversationId(),
                request.tenantId(),
                messages,
                new RetrievalSummary(intent, chunks),
                new WorkflowSummary(workflowResult.workflowId(), workflowResult.state(), workflowResult.slots(), toolResult)
        );
    }

    private String jsonLine(String type, Object payload) {
        try {
            return objectMapper.writeValueAsString(new JsonLine(type, payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize SSE line of type {}", type, e);
            return "{}";
        }
    }

    private record JsonLine(String type, Object payload) {}
}
