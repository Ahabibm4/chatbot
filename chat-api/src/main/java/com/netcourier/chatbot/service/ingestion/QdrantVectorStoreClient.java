package com.netcourier.chatbot.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class QdrantVectorStoreClient implements VectorStoreClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreClient.class);

    private final WebClient qdrantWebClient;
    private final String collection;

    public QdrantVectorStoreClient(WebClient qdrantWebClient,
                                   @Value("${chat.qdrant.collection:nc_chunks_v1}") String collection) {
        this.qdrantWebClient = qdrantWebClient;
        this.collection = collection;
    }

    @Override
    public void upsert(String tenantId, String docId, List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Point> points = chunks.stream()
                .map(chunk -> new Point(chunk.id(), chunk.vector(), payloadFor(tenantId, docId, chunk)))
                .toList();
        try {
            qdrantWebClient.put()
                    .uri("/collections/{collection}/points", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpsertRequest(points))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .onErrorResume(throwable -> {
                        log.error("Failed to upsert into Qdrant: {}", throwable.getMessage());
                        return Mono.error(new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to upsert into Qdrant", throwable));
                    })
                    .block();
        } catch (IngestionException ex) {
            throw ex;
        } catch (Exception e) {
            throw new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to upsert into Qdrant", e);
        }
    }

    private Map<String, Object> payloadFor(String tenantId, String docId, EmbeddedChunk chunk) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("doc_id", docId);
        payload.put("chunk_id", chunk.id());
        payload.put("title", chunk.title());
        payload.put("page", chunk.page());
        payload.put("text", chunk.text());
        payload.put("roles", chunk.roles());
        return payload;
    }

    private record Point(String id, List<Double> vector, Map<String, Object> payload) {}

    private record UpsertRequest(List<Point> points) {}
}
