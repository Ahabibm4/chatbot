package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public interface SearchIndexClient {

    void index(String tenantId, String docId, List<EmbeddedChunk> chunks);
}
