package com.netcourier.chatbot.service.ingestion;

import org.springframework.http.HttpStatus;

public class IngestionException extends RuntimeException {

    private final HttpStatus status;

    public IngestionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public IngestionException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
