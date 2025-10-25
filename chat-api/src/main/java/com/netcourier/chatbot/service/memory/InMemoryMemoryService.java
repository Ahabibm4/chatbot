package com.netcourier.chatbot.service.memory;

import com.netcourier.chatbot.model.ChatMessage;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("inmemory")
public class InMemoryMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryService.class);

    private final Map<String, List<ChatTurn>> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> assistantMessages = new ConcurrentHashMap<>();

    @Override
    public void appendTurns(ChatRequest request) {
        conversations.compute(request.conversationId(), (key, existing) -> {
            List<ChatTurn> turns = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            turns.addAll(request.turns());
            return turns;
        });
    }

    @Override
    public void storeAssistantMessage(ChatRequest request, ChatMessage message, WorkflowResult workflowResult) {
        assistantMessages.compute(request.conversationId(), (key, existing) -> {
            List<ChatMessage> messages = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            messages.add(message);
            return messages;
        });
        log.debug("Stored assistant message for conversation {} with workflow {}", request.conversationId(), workflowResult.workflowId());
    }
}
