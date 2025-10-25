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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class QdrantDenseRetriever implements DenseRetriever {

    private static final Logger log = LoggerFactory.getLogger(QdrantDenseRetriever.class);

    private final WebClient qdrantWebClient;
    private final String collection;
    private final int topK;
    private final String vectorName;
    private final String tenantField;
    private final String roleField;

    public QdrantDenseRetriever(WebClient qdrantWebClient,
                                @Value("${chat.qdrant.collection:nc_chunks_v1}") String collection,
                                @Value("${chat.rag.dense.top-k:8}") int topK,
                                @Value("${chat.qdrant.vector-name:text_embeddings}") String vectorName,
                                @Value("${chat.qdrant.filters.tenant-field:tenantId}") String tenantField,
                                @Value("${chat.qdrant.filters.role-field:roles}") String roleField) {
        this.qdrantWebClient = qdrantWebClient;
        this.collection = collection;
        this.topK = topK;
        this.vectorName = vectorName;
        this.tenantField = tenantField;
        this.roleField = roleField;
    }

    @Override
    public List<RetrievedChunk> search(ChatRequest request, String intent) {
        String query = Optional.ofNullable(request.turns().isEmpty() ? null : request.turns().getLast().content())
                .filter(content -> !content.isBlank())
                .orElse("Hello");
        DenseQueryPayload payload = new DenseQueryPayload(query, topK, vectorName, buildFilter(request));
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

    private QueryFilter buildFilter(ChatRequest request) {
        QueryFilterBuilder builder = new QueryFilterBuilder().mustMatch(tenantField, request.tenantId());
        if (request.context() != null && request.context().roles() != null && !request.context().roles().isEmpty()) {
            builder.mustAny(roleField, request.context().roles().stream().toList());
        }
        return builder.build();
    }

    private record DenseQueryPayload(String query, int limit, String vector, QueryFilter filter, boolean withPayload) {
        private DenseQueryPayload(String query, int limit, String vector, QueryFilter filter) {
            this(query, limit, vector, filter, true);
        }
    }

    private record QueryFilter(List<FieldCondition> must) {}

    private record FieldCondition(String key, Match match) {}

    private record Match(String value, List<String> any) {
        private static Match value(String value) {
            return new Match(value, null);
        }

        private static Match any(List<String> any) {
            return new Match(null, any);
        }
    }

    private static class QueryFilterBuilder {
        private final List<FieldCondition> must = new ArrayList<>();

        QueryFilterBuilder mustMatch(String key, String value) {
            if (value != null && !value.isBlank()) {
                must.add(new FieldCondition(key, Match.value(value)));
            }
            return this;
        }

        QueryFilterBuilder mustAny(String key, List<String> values) {
            if (values != null && !values.isEmpty()) {
                must.add(new FieldCondition(key, Match.any(values)));
            }
            return this;
        }

        QueryFilter build() {
            return must.isEmpty() ? null : new QueryFilter(List.copyOf(must));
        }
    }

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
