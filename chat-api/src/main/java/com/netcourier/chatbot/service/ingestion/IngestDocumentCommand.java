package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public record IngestDocumentCommand(String tenantId,
                                    String title,
                                    String filename,
                                    byte[] bytes,
                                    List<String> roles,
                                    String externalId,
                                    DocumentMetadata metadata) {
}
