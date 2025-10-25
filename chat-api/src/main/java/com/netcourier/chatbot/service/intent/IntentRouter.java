package com.netcourier.chatbot.service.intent;

import com.netcourier.chatbot.model.ChatRequest;

public interface IntentRouter {
    String route(ChatRequest request);
}
