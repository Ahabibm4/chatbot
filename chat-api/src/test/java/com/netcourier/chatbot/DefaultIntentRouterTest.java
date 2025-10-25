package com.netcourier.chatbot;

import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.service.intent.DefaultIntentRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIntentRouterTest {

    private final DefaultIntentRouter router = new DefaultIntentRouter("RAG_FAQ");

    @Test
    void detectsRescheduleIntent() {
        ChatRequest request = new ChatRequest("c1", "tenant", "user",
                List.of(new ChatTurn(ChatMessageRole.USER, "Please reschedule job NC123456 to tomorrow")), null);
        assertThat(router.route(request)).isEqualTo("RESCHEDULE_DELIVERY");
    }

    @Test
    void fallsBackWhenNoMatch() {
        ChatRequest request = new ChatRequest("c1", "tenant", "user",
                List.of(new ChatTurn(ChatMessageRole.USER, "Hello")), null);
        assertThat(router.route(request)).isEqualTo("RAG_FAQ");
    }
}
