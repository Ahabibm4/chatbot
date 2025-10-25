package com.netcourier.chatbot.service.retrieval;

import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HybridRagServiceTest {

    @Test
    void retrieveMergesScoresByDocIdUsingReciprocalRank() {
        List<RetrievedChunk> denseResults = List.of(
                chunk("tenant-doc-1", "Tenant Doc 1", 1, 0.9, "dense"),
                chunk("GLOBAL::doc", "Global Doc", 2, 0.8, "dense"),
                chunk("tenant-doc-2", "Tenant Doc 2", 3, 0.7, "dense")
        );
        List<RetrievedChunk> sparseResults = List.of(
                chunk("tenant-doc-1", "Tenant Doc 1 Sparse", 4, 1.5, "sparse"),
                chunk("tenant-doc-3", "Tenant Doc 3", 5, 1.2, "sparse"),
                chunk("GLOBAL::doc", "Global Doc", 6, 1.0, "sparse")
        );
        HybridRagService service = new HybridRagService(
                (request, intent) -> denseResults,
                (request, intent) -> sparseResults,
                1.0,
                1.0,
                5
        );

        List<RetrievedChunk> fused = service.retrieve(request(), "intent");

        assertThat(fused)
                .hasSize(4)
                .extracting(RetrievedChunk::docId)
                .containsExactly("tenant-doc-1", "GLOBAL::doc", "tenant-doc-3", "tenant-doc-2");
        assertThat(fused.getFirst().page()).isEqualTo(4);
        assertThat(fused.getFirst().score()).isCloseTo(2.0, within(1e-6));
        assertThat(fused.get(1).score()).isCloseTo(0.5 + (1.0 / 3.0), within(1e-6));
        assertThat(fused.get(2).score()).isCloseTo(0.5, within(1e-6));
        assertThat(fused.get(3).score()).isCloseTo(1.0 / 3.0, within(1e-6));
    }

    @Test
    void retrievePlacesTenantChunksAheadOfGlobalWhenScoresTie() {
        List<RetrievedChunk> denseResults = List.of(
                chunk("tenant-doc", "Tenant Doc", 1, 0.9, "dense"),
                chunk("GLOBAL::doc", "Global Doc", 1, 0.8, "dense")
        );
        List<RetrievedChunk> sparseResults = List.of(
                chunk("GLOBAL::doc", "Global Doc", 2, 1.1, "sparse"),
                chunk("tenant-doc", "Tenant Doc", 2, 1.0, "sparse")
        );
        HybridRagService service = new HybridRagService(
                (request, intent) -> denseResults,
                (request, intent) -> sparseResults,
                1.0,
                1.0,
                5
        );

        List<RetrievedChunk> fused = service.retrieve(request(), "intent");

        assertThat(fused)
                .hasSize(2)
                .extracting(RetrievedChunk::docId)
                .containsExactly("tenant-doc", "GLOBAL::doc");
        assertThat(fused.getFirst().score()).isCloseTo(1.5, within(1e-6));
        assertThat(fused.get(1).score()).isCloseTo(1.5, within(1e-6));
    }

    private ChatRequest request() {
        return new ChatRequest(
                "conversation",
                "tenant-1",
                "user-1",
                List.of(new ChatTurn(ChatMessageRole.USER, "Hello")),
                null
        );
    }

    private static RetrievedChunk chunk(String docId, String title, int page, double score, String source) {
        return new RetrievedChunk(docId, title, page, title + " text", score, source);
    }
}
