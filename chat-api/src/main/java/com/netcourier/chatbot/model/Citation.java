package com.netcourier.chatbot.model;

public record Citation(String docId,
                        String title,
                        int page,
                        String snippet,
                        String reference) {
}
