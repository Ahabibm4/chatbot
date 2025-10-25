package com.netcourier.chatbot.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatResponse;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.Citation;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.ToolCallResult;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.service.intent.IntentRouter;
import com.netcourier.chatbot.service.memory.MemoryService;
import com.netcourier.chatbot.service.orchestration.GuardedResponse;
import com.netcourier.chatbot.service.orchestration.GuardedStreamSegment;
import com.netcourier.chatbot.service.orchestration.OrchestrationService;
import com.netcourier.chatbot.service.retrieval.RagService;
import com.netcourier.chatbot.service.tools.ToolExecutionResult;
import com.netcourier.chatbot.service.tools.ToolRegistry;
import com.netcourier.chatbot.service.workflow.WorkflowEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DefaultChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatService.class);
    private static final String NDJSON_GUARDRAIL_METRIC = "chat.guardrail.actions";
    private static final String NDJSON_TTFT_METRIC = "chat.ttft";
    private static final String NDJSON_CITATION_METRIC = "chat.citations";

    private final IntentRouter intentRouter;
    private final RagService ragService;
    private final WorkflowEngine workflowEngine;
    private final MemoryService memoryService;
    private final OrchestrationService orchestrationService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DefaultChatService(IntentRouter intentRouter,
                              RagService ragService,
                              WorkflowEngine workflowEngine,
                              MemoryService memoryService,
                              OrchestrationService orchestrationService,
                              ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.intentRouter = intentRouter;
        this.ragService = ragService;
        this.workflowEngine = workflowEngine;
        this.memoryService = memoryService;
        this.orchestrationService = orchestrationService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Timer.Sample ttft = Timer.start(meterRegistry);

        memoryService.appendTurns(request);

        emit(sink, frame("thinking", "router", null));

        var intent = intentRouter.route(request);
        List<RetrievedChunk> chunks = ragService.retrieve(request, intent);

        WorkflowResult workflowResult = workflowEngine.handle(request, intent);
        if (workflowResult.toolToInvoke().isPresent()) {
            ToolExecutionResult result = toolRegistry.execute(workflowResult.toolToInvoke().get(), request, workflowResult.slots());
            workflowResult = workflowResult.withToolResult(result.toModel());
            Map<String, Object> toolData = new HashMap<>();
            toolData.put("tool", result.toolName());
            toolData.put("success", result.success());
            emit(sink, frame("tool_result", result.detail(), toolData));
        }

        AtomicReference<WorkflowResult> workflowState = new AtomicReference<>(workflowResult);
        AtomicBoolean finalEmitted = new AtomicBoolean(false);

        orchestrationService.orchestrateStream(request, intent, chunks, workflowState.get())
                .subscribe(segment -> {
                    if (segment.type() == GuardedStreamSegment.Type.PARTIAL) {
                        if (segment.text() != null && !segment.text().isBlank()) {
                            emit(sink, frame("partial", segment.text(), null));
                        }
                        return;
                    }
                    finalEmitted.set(true);
                    completeStream(sink, request, workflowState, intent, chunks, ttft, segment);
                }, error -> {
                    log.error("Streaming orchestration failed", error);
                    finalEmitted.set(true);
                    GuardedStreamSegment fallback = GuardedStreamSegment.finalSegment(
                            fallbackMessage(),
                            List.of(),
                            "ERROR"
                    );
                    completeStream(sink, request, workflowState, intent, chunks, ttft, fallback);
                }, () -> {
                    if (!finalEmitted.get()) {
                        GuardedStreamSegment fallback = GuardedStreamSegment.finalSegment(
                                fallbackMessage(),
                                List.of(),
                                "ERROR"
                        );
                        completeStream(sink, request, workflowState, intent, chunks, ttft, fallback);
                    }
                });

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
        GuardedResponse orchestrated = orchestrationService.orchestrate(request, intent, chunks, workflowResult);
        workflowResult = workflowResult.withResponse(orchestrated.answer());

        String guardrailAction = orchestrated.guardrailAction();
        String finalAnswer = orchestrated.answer();
        List<Citation> citations = orchestrated.citations() == null ? List.of() : orchestrated.citations();

        boolean ragIntent = intent != null && intent.toUpperCase(Locale.ROOT).startsWith("RAG");
        boolean hasSnippets = chunks != null && !chunks.isEmpty();
        if (ragIntent && !hasSnippets) {
            finalAnswer = "I don't know that yet. You can upload a document or try re-phrasing your question.";
            citations = List.of();
        }
        workflowResult = workflowResult.withResponse(finalAnswer);

        ChatMessage assistantMessage = new ChatMessage(
                UUID.randomUUID(),
                ChatMessageRole.ASSISTANT,
                finalAnswer,
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
                new WorkflowSummary(workflowResult.workflowId(), workflowResult.state(), workflowResult.slots(), toolResult),
                citations,
                guardrailAction
        );
    }

    private void emit(Sinks.Many<String> sink, NdjsonFrame frame) {
        sink.tryEmitNext(toJson(frame) + "\n");
    }

    private void completeStream(Sinks.Many<String> sink,
                                ChatRequest request,
                                AtomicReference<WorkflowResult> workflowState,
                                String intent,
                                List<RetrievedChunk> chunks,
                                Timer.Sample ttft,
                                GuardedStreamSegment finalSegment) {
        String guardrailAction = finalSegment.guardrailAction();
        List<Citation> citations = finalSegment.citations() == null ? List.of() : finalSegment.citations();
        String finalAnswer = finalSegment.text() == null ? fallbackMessage() : finalSegment.text().trim();

        boolean ragIntent = intent != null && intent.toUpperCase(Locale.ROOT).startsWith("RAG");
        boolean hasSnippets = chunks != null && !chunks.isEmpty();
        if (ragIntent && !hasSnippets) {
            finalAnswer = "I don't know that yet. You can upload a document or try re-phrasing your question.";
            citations = List.of();
            guardrailAction = guardrailAction == null ? "ALLOW" : guardrailAction;
        }

        WorkflowResult updatedWorkflow = workflowState.get().withResponse(finalAnswer);
        workflowState.set(updatedWorkflow);

        if (ragIntent) {
            meterRegistry.counter(NDJSON_CITATION_METRIC,
                            "tenant", request.tenantId(),
                            "present", Boolean.toString(!citations.isEmpty()))
                    .increment();
        }

        if (guardrailAction != null && !"ALLOW".equalsIgnoreCase(guardrailAction)) {
            meterRegistry.counter(NDJSON_GUARDRAIL_METRIC, "action", guardrailAction).increment();
        }

        Map<String, Object> finalData = new HashMap<>();
        finalData.put("intent", intent);
        finalData.put("citations", citations);
        finalData.put("guardrailAction", guardrailAction);

        ChatMessage assistantMessage = new ChatMessage(
                UUID.randomUUID(),
                ChatMessageRole.ASSISTANT,
                finalAnswer,
                OffsetDateTime.now(),
                true
        );
        emit(sink, frame("final", finalAnswer, finalData));

        memoryService.storeAssistantMessage(request, assistantMessage, updatedWorkflow);

        ttft.stop(meterRegistry.timer(NDJSON_TTFT_METRIC,
                "tenant", request.tenantId(),
                "intent", intent == null ? "UNKNOWN" : intent));

        sink.tryEmitComplete();
    }

    private String fallbackMessage() {
        return "I'm having trouble reaching the AI assistant right now. Please try again in a moment or contact a NetCourier agent.";
    }

    private String toJson(NdjsonFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize NDJSON frame of type {}", frame.type(), e);
            return "{\"type\":\"error\"}";
        }
    }

    private record NdjsonFrame(String type, String text, @JsonInclude(JsonInclude.Include.NON_NULL) Object data) {
    }

    private NdjsonFrame frame(String type, String text, Object data) {
        return new NdjsonFrame(type, text, data);
    }
}
