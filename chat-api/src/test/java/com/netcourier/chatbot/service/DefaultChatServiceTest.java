package com.netcourier.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.service.intent.IntentRouter;
import com.netcourier.chatbot.service.memory.MemoryService;
import com.netcourier.chatbot.service.orchestration.GuardedStreamSegment;
import com.netcourier.chatbot.service.orchestration.OrchestrationService;
import com.netcourier.chatbot.service.retrieval.RagService;
import com.netcourier.chatbot.service.tools.ToolRegistry;
import com.netcourier.chatbot.service.workflow.WorkflowEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultChatServiceTest {

    private final IntentRouter intentRouter = mock(IntentRouter.class);
    private final RagService ragService = mock(RagService.class);
    private final WorkflowEngine workflowEngine = mock(WorkflowEngine.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final OrchestrationService orchestrationService = mock(OrchestrationService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DefaultChatService service;

    @BeforeEach
    void setUp() {
        service = new DefaultChatService(
                intentRouter,
                ragService,
                workflowEngine,
                memoryService,
                orchestrationService,
                toolRegistry,
                objectMapper,
                meterRegistry
        );
    }

    @Test
    void streamChatEmitsPartialFramesBeforeFinal() {
        ChatRequest request = new ChatRequest(
                "conversation-1",
                "tenant-123",
                "user-456",
                List.of(new ChatTurn(ChatMessageRole.USER, "Hello")),
                null
        );
        WorkflowResult workflowResult = new WorkflowResult(
                "workflow-1",
                "READY",
                Map.of(),
                Optional.empty(),
                null,
                null
        );

        when(intentRouter.route(any())).thenReturn("SUPPORT");
        when(ragService.retrieve(any(), eq("SUPPORT"))).thenReturn(List.of());
        when(workflowEngine.handle(any(), eq("SUPPORT"))).thenReturn(workflowResult);
        when(orchestrationService.orchestrateStream(any(), eq("SUPPORT"), any(), any())).thenReturn(Flux.just(
                GuardedStreamSegment.partial("Hel"),
                GuardedStreamSegment.partial("lo"),
                GuardedStreamSegment.finalSegment("Hello", List.of(), "ALLOW")
        ));

        StepVerifier.create(service.streamChat(request))
                .consumeNextWith(frame -> assertFrame(frame, "thinking", null))
                .consumeNextWith(frame -> assertFrame(frame, "partial", "Hel"))
                .consumeNextWith(frame -> assertFrame(frame, "partial", "lo"))
                .consumeNextWith(frame -> {
                    JsonNode node = read(frame);
                    assertThat(node.get("type").asText()).isEqualTo("final");
                    assertThat(node.get("text").asText()).isEqualTo("Hello");
                    assertThat(node.get("data").get("guardrailAction").asText()).isEqualTo("ALLOW");
                })
                .verifyComplete();

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrationService).orchestrateStream(requestCaptor.capture(), eq("SUPPORT"), any(), any());
        assertThat(requestCaptor.getValue().turns()).hasSize(1);
        verify(memoryService).storeAssistantMessage(any(), any(), any());
    }

    private void assertFrame(String frame, String expectedType, String expectedText) {
        JsonNode node = read(frame);
        assertThat(node.get("type").asText()).isEqualTo(expectedType);
        if (expectedText != null) {
            assertThat(node.get("text").asText()).isEqualTo(expectedText);
        }
    }

    private JsonNode read(String frame) {
        try {
            return objectMapper.readTree(frame);
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse frame: " + frame, ex);
        }
    }
}
