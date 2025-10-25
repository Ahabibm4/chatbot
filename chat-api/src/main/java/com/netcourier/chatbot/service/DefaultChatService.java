package com.netcourier.chatbot.service;

import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatResponse;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.Citation;
import com.netcourier.chatbot.model.ChatEvent;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.ToolCallResult;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.service.intent.IntentRouter;
import com.netcourier.chatbot.service.memory.MemoryService;
import com.netcourier.chatbot.service.orchestration.GuardedResponse;
import com.netcourier.chatbot.service.orchestration.OrchestrationService;
import com.netcourier.chatbot.service.retrieval.RagService;
import com.netcourier.chatbot.service.tools.ToolExecutionResult;
import com.netcourier.chatbot.service.tools.ToolRegistry;
import com.netcourier.chatbot.service.workflow.WorkflowEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

@Service
public class DefaultChatService implements ChatService {

    private static final String NDJSON_GUARDRAIL_METRIC = "chat.guardrail.actions";
    private static final String NDJSON_TTFT_METRIC = "chat.ttft";
    private static final String NDJSON_CITATION_METRIC = "chat.citations";

    private final IntentRouter intentRouter;
    private final RagService ragService;
    private final WorkflowEngine workflowEngine;
    private final MemoryService memoryService;
    private final OrchestrationService orchestrationService;
    private final ToolRegistry toolRegistry;
    private final MeterRegistry meterRegistry;

    public DefaultChatService(IntentRouter intentRouter,
                              RagService ragService,
                              WorkflowEngine workflowEngine,
                              MemoryService memoryService,
                              OrchestrationService orchestrationService,
                              ToolRegistry toolRegistry,
                              MeterRegistry meterRegistry) {
        this.intentRouter = intentRouter;
        this.ragService = ragService;
        this.workflowEngine = workflowEngine;
        this.memoryService = memoryService;
        this.orchestrationService = orchestrationService;
        this.toolRegistry = toolRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Flux<ChatEvent> streamChat(ChatRequest request) {
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Timer.Sample ttft = Timer.start(meterRegistry);

        memoryService.appendTurns(request);

        emit(sink, ChatEvent.thinking("router", null));

        var intent = intentRouter.route(request);
        List<RetrievedChunk> chunks = ragService.retrieve(request, intent);

        WorkflowResult workflowResult = workflowEngine.handle(request, intent);
        if (workflowResult.toolToInvoke().isPresent()) {
            ToolExecutionResult result = toolRegistry.execute(workflowResult.toolToInvoke().get(), request, workflowResult.slots());
            workflowResult = workflowResult.withToolResult(result.toModel());
            Map<String, Object> toolData = new HashMap<>();
            toolData.put("tool", result.toolName());
            toolData.put("success", result.success());
            emit(sink, ChatEvent.toolResult(result.detail(), toolData));
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
            guardrailAction = guardrailAction == null ? "ALLOW" : guardrailAction;
        }
        workflowResult = workflowResult.withResponse(finalAnswer);

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
        List<String> partials = buildPartialMessages(finalAnswer);
        for (int i = 0; i < partials.size(); i++) {
            Map<String, Object> partialMetadata = new HashMap<>();
            partialMetadata.put("index", i);
            partialMetadata.put("total", partials.size());
            emit(sink, ChatEvent.partial(partials.get(i), partialMetadata));
        }

        emit(sink, ChatEvent.finalResponse(finalAnswer, finalData));

        memoryService.storeAssistantMessage(request, assistantMessage, workflowResult);

        ttft.stop(meterRegistry.timer(NDJSON_TTFT_METRIC,
                "tenant", request.tenantId(),
                "intent", intent == null ? "UNKNOWN" : intent));

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

    private void emit(Sinks.Many<ChatEvent> sink, ChatEvent event) {
        sink.tryEmitNext(event);
    }

    private List<String> buildPartialMessages(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> partials = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(word);
            partials.add(builder.toString());
        }
        if (partials.isEmpty()) {
            partials.add(text);
        }
        return partials;
    }
}
