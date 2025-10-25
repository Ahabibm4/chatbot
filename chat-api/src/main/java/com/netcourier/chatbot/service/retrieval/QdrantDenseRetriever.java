package com.netcourier.chatbot.service.retrieval;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class QdrantDenseRetriever implements DenseRetriever {

    private static final Logger log = LoggerFactory.getLogger(QdrantDenseRetriever.class);

    private final WebClient qdrantWebClient;
    private final String collection;
    private final int topK;

    public QdrantDenseRetriever(WebClient qdrantWebClient,
                                @Value("${chat.qdrant.collection:nc_chunks_v1}") String collection,
                                @Value("${chat.rag.dense.top-k:8}") int topK) {
        this.qdrantWebClient = qdrantWebClient;
        this.collection = collection;
        this.topK = topK;
    }

    @Override
    public List<RetrievedChunk> search(ChatRequest request, String intent) {
        String query = Optional.ofNullable(request.turns().isEmpty() ? null : request.turns().getLast().content())
                .filter(content -> !content.isBlank())
                .orElse("Hello");
        DenseQueryPayload payload = new DenseQueryPayload(query, request.tenantId(), topK);
        try {
            QdrantResponse response = qdrantWebClient.post()
                    .uri("/collections/{collection}/points/search", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(QdrantResponse.class)
                    .onErrorResume(throwable -> {
                        log.warn("Qdrant search failed: {}", throwable.getMessage());
                        return Mono.just(new QdrantResponse(Collections.emptyList()));
                    })
                    .block();
            if (response == null) {
                return Collections.emptyList();
            }
            return response.toChunks();
        } catch (Exception e) {
            log.warn("Failed to query Qdrant", e);
            return Collections.emptyList();
        }
    }

    private record DenseQueryPayload(String query, String tenantId, int limit) {}

    private record QdrantResponse(List<Result> result) {
        List<RetrievedChunk> toChunks() {
            return result == null ? Collections.emptyList() : result.stream().map(Result::toChunk).toList();
        }
    }

    private record Result(double score, Payload payload) {
        RetrievedChunk toChunk() {
            if (payload == null) {
                return new RetrievedChunk("", "", 0, "", score, "qdrant");
            }
            return new RetrievedChunk(payload.docId(), payload.title(), payload.page(), payload.text(), score, "qdrant");
        }
    }

    private record Payload(String docId, String title, int page, String text) {}
}
