package com.netcourier.chatbot.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.persistence.entity.WorkflowStateKey;
import com.netcourier.chatbot.persistence.repository.ChatTurnRepository;
import com.netcourier.chatbot.persistence.repository.ConversationRepository;
import com.netcourier.chatbot.persistence.repository.WorkflowStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JpaMemoryServiceTest {

    @Autowired
    private JpaMemoryService memoryService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatTurnRepository chatTurnRepository;

    @Autowired
    private WorkflowStateRepository workflowStateRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storesConversationTurnsAndWorkflowState() throws Exception {
        ChatRequest request = new ChatRequest(
                "conv-123",
                "tenant-abc",
                "user-1",
                List.of(
                        new ChatTurn(ChatMessageRole.USER, "I need to reschedule NC123456"),
                        new ChatTurn(ChatMessageRole.USER, "Tomorrow would be perfect")
                ),
                null
        );

        memoryService.appendTurns(request);

        ChatMessage assistant = new ChatMessage(
                java.util.UUID.randomUUID(),
                ChatMessageRole.ASSISTANT,
                "I'll take care of that reschedule.",
                OffsetDateTime.now(),
                false
        );
        WorkflowResult workflowResult = new WorkflowResult(
                "RESCHEDULE_DELIVERY",
                "RESCHEDULE_READY",
                Map.of("jobId", "NC123456", "newWindow", "tomorrow"),
                Optional.of("RESCHEDULE_DELIVERY"),
                assistant.content(),
                null
        );

        memoryService.storeAssistantMessage(request, assistant, workflowResult);

        assertThat(conversationRepository.findById("conv-123")).isPresent();
        assertThat(chatTurnRepository.findByConversationIdOrderBySequenceAsc("conv-123"))
                .hasSize(3)
                .extracting("role")
                .containsExactly(ChatMessageRole.USER, ChatMessageRole.USER, ChatMessageRole.ASSISTANT);

        WorkflowStateKey key = new WorkflowStateKey("conv-123", "RESCHEDULE_DELIVERY");
        var persistedState = workflowStateRepository.findById(key);
        assertThat(persistedState).isPresent();
        assertThat(persistedState.get().getState()).isEqualTo("RESCHEDULE_READY");
        Map<?, ?> slots = objectMapper.readValue(persistedState.get().getSlotsJson(), Map.class);
        assertThat(slots)
                .containsEntry("jobId", "NC123456")
                .containsEntry("newWindow", "tomorrow");
    }
}
