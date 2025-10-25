package com.netcourier.chatbot.controller;

import com.netcourier.chatbot.model.ChatSubmission;
import com.netcourier.chatbot.model.ChatSubmissionContext;
import com.netcourier.chatbot.service.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatController controller = new ChatController(chatService);

    @Test
    void streamEmitsPartialEventsBeforeFinal() {
        ChatSubmission submission = new ChatSubmission(
                "session-1",
                "Hello",
                new ChatSubmissionContext("tenant", "user", "web", "en", List.of("agent"))
        );

        when(chatService.streamChat(any())).thenReturn(Flux.just(
                "{\"type\":\"partial\",\"text\":\"Hel\"}\n",
                "{\"type\":\"partial\",\"text\":\"lo\"}\n",
                "{\"type\":\"final\",\"text\":\"Hello\"}\n"
        ));

        StepVerifier.create(controller.stream(submission))
                .expectNextMatches(frame -> frame.contains("\"partial\""))
                .expectNextMatches(frame -> frame.contains("\"partial\""))
                .expectNextMatches(frame -> frame.contains("\"final\""))
                .verifyComplete();

        ArgumentCaptor<com.netcourier.chatbot.model.ChatRequest> requestCaptor = ArgumentCaptor.forClass(com.netcourier.chatbot.model.ChatRequest.class);
        verify(chatService).streamChat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().turns()).isNotEmpty();
    }
}
