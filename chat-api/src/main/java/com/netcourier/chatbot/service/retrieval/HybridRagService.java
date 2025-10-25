package com.netcourier.chatbot.service.retrieval;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        applyReciprocalRankScores(dense, denseWeight, fused);
        applyReciprocalRankScores(sparse, sparseWeight, fused);
        return fused.values().stream()
                .sorted(Comparator
                        .comparingDouble(RetrievedChunkScore::score)
                        .reversed()
                        .thenComparing(RetrievedChunkScore::global)
                        .thenComparing(score -> score.chunk().docId(), Comparator.nullsLast(String::compareTo)))
                .limit(resultLimit)
                .map(RetrievedChunkScore::chunkWithFusedScore)
                .toList();
    }

    private void applyReciprocalRankScores(List<RetrievedChunk> chunks,
                                           double weight,
                                           Map<String, RetrievedChunkScore> fused) {
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            double contribution = weight / (i + 1d);
            fused.merge(key(chunk),
                    new RetrievedChunkScore(chunk, contribution, chunk.score(), isGlobalChunk(chunk)),
                    RetrievedChunkScore::merge);
        }
    }

    private String key(RetrievedChunk chunk) {
        return chunk.docId() == null ? "" : chunk.docId();
    }

    private boolean isGlobalChunk(RetrievedChunk chunk) {
        if (chunk.docId() == null) {
            return false;
        }
        String normalized = chunk.docId().toUpperCase(Locale.ROOT);
        return normalized.startsWith("GLOBAL");
    }

    private record RetrievedChunkScore(RetrievedChunk chunk, double score, double bestSourceScore, boolean global) {
        RetrievedChunkScore merge(RetrievedChunkScore other) {
            RetrievedChunk bestChunk = chunk;
            double bestScore = bestSourceScore;
            if (other.bestSourceScore > bestSourceScore) {
                bestChunk = other.chunk;
                bestScore = other.bestSourceScore;
            }
            return new RetrievedChunkScore(bestChunk, score + other.score, bestScore, global || other.global);
        }

        RetrievedChunk chunkWithFusedScore() {
            return new RetrievedChunk(chunk.docId(), chunk.title(), chunk.page(), chunk.text(), score, chunk.source());
        }
    }
}
