package com.netcourier.chatbot.service;

import com.netcourier.chatbot.model.ChatEvent;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface ChatService {

    Flux<ChatEvent> streamChat(ChatRequest request);

    ChatResponse completeChat(ChatRequest request);
}
