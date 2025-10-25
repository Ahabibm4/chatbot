package com.netcourier.chatbot.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.persistence.entity.ChatTurnEntity;
import com.netcourier.chatbot.persistence.entity.ConversationEntity;
import com.netcourier.chatbot.persistence.entity.WorkflowStateEntity;
import com.netcourier.chatbot.persistence.entity.WorkflowStateKey;
import com.netcourier.chatbot.persistence.repository.ChatTurnRepository;
import com.netcourier.chatbot.persistence.repository.ConversationRepository;
import com.netcourier.chatbot.persistence.repository.WorkflowStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
@Primary
@Transactional
public class JpaMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(JpaMemoryService.class);

    private final ConversationRepository conversationRepository;
    private final ChatTurnRepository chatTurnRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final ObjectMapper objectMapper;

    public JpaMemoryService(ConversationRepository conversationRepository,
                            ChatTurnRepository chatTurnRepository,
                            WorkflowStateRepository workflowStateRepository,
                            ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.chatTurnRepository = chatTurnRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void appendTurns(ChatRequest request) {
        ensureConversation(request);
        int sequence = chatTurnRepository.findMaxSequence(request.conversationId());
        for (var turn : request.turns()) {
            sequence++;
            ChatTurnEntity entity = new ChatTurnEntity(
                    request.conversationId(),
                    request.tenantId(),
                    turn.role(),
                    turn.content(),
                    sequence,
                    OffsetDateTime.now()
            );
            chatTurnRepository.save(entity);
        }
    }

    @Override
    public void storeAssistantMessage(ChatRequest request, ChatMessage message, WorkflowResult workflowResult) {
        ensureConversation(request);
        int sequence = chatTurnRepository.findMaxSequence(request.conversationId()) + 1;
        ChatTurnEntity entity = new ChatTurnEntity(
                request.conversationId(),
                request.tenantId(),
                message.role(),
                message.content(),
                sequence,
                message.timestamp()
        );
        chatTurnRepository.save(entity);

        if (workflowResult != null) {
            WorkflowStateKey key = new WorkflowStateKey(request.conversationId(), workflowResult.workflowId());
            WorkflowStateEntity state = workflowStateRepository.findById(key)
                    .orElseGet(() -> new WorkflowStateEntity(key, workflowResult.state(), toJson(workflowResult.slots())));
            state.setState(workflowResult.state());
            state.setSlotsJson(toJson(workflowResult.slots()));
            state.setLastResponse(workflowResult.responseMessage());
            state.setToolName(workflowResult.toolToInvoke().orElse(null));
            workflowStateRepository.save(state);
            log.debug("Persisted workflow state {} for conversation {}", workflowResult.workflowId(), request.conversationId());
        }
    }

    private void ensureConversation(ChatRequest request) {
        conversationRepository.findById(request.conversationId())
                .orElseGet(() -> conversationRepository.save(new ConversationEntity(request.conversationId(), request.tenantId())));
    }

    private String toJson(Map<String, Object> slots) {
        try {
            return objectMapper.writeValueAsString(slots == null ? Map.of() : slots);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow slots", e);
        }
    }
}
