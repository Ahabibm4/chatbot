package com.netcourier.chatbot.service.ingestion;

import java.util.List;

public interface EmbeddingsClient {

    EmbeddingBatch embed(List<String> texts);

    record EmbeddingBatch(List<List<Double>> vectors, String model, int dimensions) {}
}
