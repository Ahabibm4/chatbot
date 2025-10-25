package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public interface TextChunker {

    List<String> chunk(String title, String text);
}
