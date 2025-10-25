package com.netcourier.chatbot.service.orchestration.openai;

public class OpenAiChatException extends RuntimeException {

    public OpenAiChatException(String message) {
        super(message);
    }

    public OpenAiChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
