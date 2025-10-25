package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public record IngestTextCommand(String tenantId,
                                String title,
                                String text,
                                List<String> roles) {
}
