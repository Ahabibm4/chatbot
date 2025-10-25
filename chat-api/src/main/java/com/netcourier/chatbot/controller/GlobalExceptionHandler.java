package com.netcourier.chatbot.controller;

import com.netcourier.chatbot.service.ingestion.IngestionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<Map<String, Object>> handleIngestionException(IngestionException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of(
                        "error", exception.getMessage()
                ));
    }
}
