package com.netcourier.chatbot.service.retrieval;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRagService implements RagService {

    private final DenseRetriever denseRetriever;
    private final SparseRetriever sparseRetriever;
    private final double denseWeight;
    private final double sparseWeight;
    private final int resultLimit;

    public HybridRagService(DenseRetriever denseRetriever,
                            SparseRetriever sparseRetriever,
                            @Value("${chat.rag.dense.weight:0.6}") double denseWeight,
                            @Value("${chat.rag.sparse.weight:0.4}") double sparseWeight,
                            @Value("${chat.rag.hybrid.limit:5}") int resultLimit) {
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
        this.denseWeight = denseWeight;
        this.sparseWeight = sparseWeight;
        this.resultLimit = resultLimit;
    }

    @Override
    public List<RetrievedChunk> retrieve(ChatRequest request, String intent) {
        List<RetrievedChunk> dense = denseRetriever.search(request, intent);
        List<RetrievedChunk> sparse = sparseRetriever.search(request, intent);
        Map<String, RetrievedChunkScore> fused = new LinkedHashMap<>();
        dense.forEach(chunk -> fused.merge(key(chunk), new RetrievedChunkScore(chunk, chunk.score() * denseWeight), RetrievedChunkScore::merge));
        sparse.forEach(chunk -> fused.merge(key(chunk), new RetrievedChunkScore(chunk, chunk.score() * sparseWeight), RetrievedChunkScore::merge));
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunkScore::score).reversed())
                .limit(resultLimit)
                .map(RetrievedChunkScore::chunk)
                .toList();
    }

    private String key(RetrievedChunk chunk) {
        return chunk.docId() + ":" + chunk.page();
    }

    private record RetrievedChunkScore(RetrievedChunk chunk, double score) {
        RetrievedChunkScore merge(RetrievedChunkScore other) {
            if (other.score > score) {
                return new RetrievedChunkScore(other.chunk, score + other.score);
            }
            return new RetrievedChunkScore(chunk, score + other.score);
        }
    }
}
