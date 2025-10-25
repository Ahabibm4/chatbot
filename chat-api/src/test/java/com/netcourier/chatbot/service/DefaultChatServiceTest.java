package com.netcourier.chatbot.service;

import com.netcourier.chatbot.model.ChatContext;
import com.netcourier.chatbot.model.ChatEvent;
import com.netcourier.chatbot.model.ChatEventType;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.Citation;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.service.intent.IntentRouter;
import com.netcourier.chatbot.service.memory.MemoryService;
import com.netcourier.chatbot.service.orchestration.GuardedResponse;
import com.netcourier.chatbot.service.orchestration.OrchestrationService;
import com.netcourier.chatbot.service.retrieval.RagService;
import com.netcourier.chatbot.service.tools.ToolRegistry;
import com.netcourier.chatbot.service.workflow.WorkflowEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultChatServiceTest {

    private final IntentRouter intentRouter = mock(IntentRouter.class);
    private final RagService ragService = mock(RagService.class);
    private final WorkflowEngine workflowEngine = mock(WorkflowEngine.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final OrchestrationService orchestrationService = mock(OrchestrationService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DefaultChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new DefaultChatService(
                intentRouter,
                ragService,
                workflowEngine,
                memoryService,
                orchestrationService,
                toolRegistry,
                meterRegistry
        );
    }

    @Test
    void streamChatEmitsOrderedPartialEvents() {
        when(intentRouter.route(any())).thenReturn("RAG_FAQ");
        when(ragService.retrieve(any(), any())).thenReturn(List.of(new RetrievedChunk("doc", "Doc", 1, "text", 0.9, "source")));
        WorkflowResult workflowResult = new WorkflowResult("wf", "DONE", Map.of(), Optional.empty(), null, null);
        when(workflowEngine.handle(any(), any())).thenReturn(workflowResult);

        List<Citation> citations = List.of(new Citation("doc", "Doc", 1, "Snippet", "Doc · p.1"));
        GuardedResponse response = new GuardedResponse("Thanks for reaching out", citations, "ALLOW");
        when(orchestrationService.orchestrate(any(), any(), any(), any())).thenReturn(response);

        ChatRequest request = new ChatRequest(
                "conversation",
                "tenant",
                "user",
                List.of(new ChatTurn(ChatMessageRole.USER, "Hello")),
                new ChatContext(Locale.ENGLISH, Set.of("CP"), "CP")
        );

        List<ChatEvent> events = chatService.streamChat(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events)
                .extracting(ChatEvent::type)
                .contains(ChatEventType.THINKING, ChatEventType.FINAL);

        assertThat(events)
                .filteredOn(event -> event.type() == ChatEventType.PARTIAL)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.text()).isNotBlank());

        ChatEvent finalEvent = events.get(events.size() - 1);
        assertThat(finalEvent.type()).isEqualTo(ChatEventType.FINAL);
        assertThat(finalEvent.metadata()).isNotNull();
        assertThat(finalEvent.metadata().get("citations")).isEqualTo(citations);
    }
}
