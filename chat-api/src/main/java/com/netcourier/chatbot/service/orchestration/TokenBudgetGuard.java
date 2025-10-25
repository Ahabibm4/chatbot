package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

public class TokenBudgetGuard {

    private final int maxTokens;

    public TokenBudgetGuard(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public GuardedChunks enforce(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new GuardedChunks(List.of(), false);
        }
        int budget = maxTokens;
        List<RetrievedChunk> accepted = new ArrayList<>();
        boolean truncated = false;
        for (RetrievedChunk chunk : chunks) {
            int estimatedTokens = estimateTokens(chunk.text());
            if (estimatedTokens > budget) {
                truncated = true;
                break;
            }
            budget -= estimatedTokens;
            accepted.add(chunk);
        }
        return new GuardedChunks(List.copyOf(accepted), truncated);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int length = text.length();
        return Math.max(1, length / 4 + 16);
    }

    public record GuardedChunks(List<RetrievedChunk> chunks, boolean truncated) {}
}
