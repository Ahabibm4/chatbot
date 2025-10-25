package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public record EmbeddedChunk(String id,
                            String title,
                            int page,
                            String text,
                            List<String> roles,
                            List<Double> vector) {
}
