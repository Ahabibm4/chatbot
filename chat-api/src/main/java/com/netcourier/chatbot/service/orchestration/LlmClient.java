package com.netcourier.chatbot.service.orchestration;

public interface LlmClient {

    LlmResponse generate(LlmRequest request);
}
