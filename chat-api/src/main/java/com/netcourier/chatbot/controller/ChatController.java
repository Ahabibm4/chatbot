package com.netcourier.chatbot.controller;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatRequestFactory;
import com.netcourier.chatbot.model.ChatResponse;
import com.netcourier.chatbot.model.ChatSubmission;
import com.netcourier.chatbot.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatSubmission submission) {
        ChatRequest request = ChatRequestFactory.fromSubmission(submission);
        return chatService.streamChat(request);
    }

    @PostMapping(path = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse complete(@Valid @RequestBody ChatSubmission submission) {
        ChatRequest request = ChatRequestFactory.fromSubmission(submission);
        return chatService.completeChat(request);
    }
}
