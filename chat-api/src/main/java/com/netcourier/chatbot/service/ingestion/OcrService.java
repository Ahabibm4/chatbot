package com.netcourier.chatbot.service.ingestion;

import java.io.InputStream;

public interface OcrService {

    String extractText(String filename, InputStream inputStream);
}
