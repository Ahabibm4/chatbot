package com.netcourier.chatbot.service.tools;

import org.springframework.web.reactive.function.client.WebClient;

public abstract class BaseApiToolAdapter implements ToolAdapter {

    protected final WebClient apiClient;

    protected BaseApiToolAdapter(WebClient apiClient) {
        this.apiClient = apiClient;
    }
}
