package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public interface VectorStoreClient {

    void upsert(String tenantId, String docId, List<EmbeddedChunk> chunks);
}
