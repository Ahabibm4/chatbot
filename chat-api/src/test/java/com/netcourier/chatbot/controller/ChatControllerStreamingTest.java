package com.netcourier.chatbot.controller;

import com.netcourier.chatbot.model.ChatEvent;
import com.netcourier.chatbot.model.ChatEventType;
import com.netcourier.chatbot.model.ChatSubmission;
import com.netcourier.chatbot.model.ChatSubmissionContext;
import com.netcourier.chatbot.service.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerStreamingTest {

    @Test
    void streamEmitsPartialEventsBeforeFinal() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);

        ChatEvent thinking = ChatEvent.thinking("router", null);
        ChatEvent firstPartial = ChatEvent.partial("Hello", Map.of("index", 0, "total", 2));
        ChatEvent secondPartial = ChatEvent.partial("Hello world", Map.of("index", 1, "total", 2));
        ChatEvent finalEvent = ChatEvent.finalResponse("Hello world", Map.of(
                "citations", List.of(Map.of("reference", "Doc · p.1")),
                "guardrailAction", "ALLOW"
        ));

        when(chatService.streamChat(any())).thenReturn(Flux.just(thinking, firstPartial, secondPartial, finalEvent));

        ChatSubmission submission = new ChatSubmission(
                "session-1",
                "Hello",
                new ChatSubmissionContext("tenant", "user", "CP", "en", List.of())
        );

        WebTestClient client = WebTestClient.bindToController(controller).build();

        List<ChatEvent> events = client.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(submission)
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatEvent.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events)
                .hasSize(4)
                .extracting(ChatEvent::type)
                .containsExactly(ChatEventType.THINKING, ChatEventType.PARTIAL, ChatEventType.PARTIAL, ChatEventType.FINAL);

        ArgumentCaptor<com.netcourier.chatbot.model.ChatRequest> requestCaptor = ArgumentCaptor.forClass(com.netcourier.chatbot.model.ChatRequest.class);
        verify(chatService).streamChat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().conversationId()).isEqualTo("session-1");
    }
}
