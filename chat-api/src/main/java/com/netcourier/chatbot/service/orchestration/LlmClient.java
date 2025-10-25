package com.netcourier.chatbot.service.orchestration;

import reactor.core.publisher.Flux;

public interface LlmClient {

    LlmResponse generate(LlmRequest request);

    Flux<LlmStreamEvent> stream(LlmRequest request);
}
