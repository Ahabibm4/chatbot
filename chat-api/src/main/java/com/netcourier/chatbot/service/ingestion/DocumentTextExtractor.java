package com.netcourier.chatbot.service.ingestion;

import java.io.InputStream;

public interface DocumentTextExtractor {

    ExtractedDocument extract(String filename, InputStream inputStream);

    record ExtractedDocument(DocumentMetadata metadata, String text) {}
}
