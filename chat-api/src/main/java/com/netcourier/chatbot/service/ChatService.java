package com.netcourier.chatbot.service;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface ChatService {

    Flux<String> streamChat(ChatRequest request);

    ChatResponse completeChat(ChatRequest request);
}
