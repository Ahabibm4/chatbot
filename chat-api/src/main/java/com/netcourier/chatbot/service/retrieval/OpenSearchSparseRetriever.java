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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenSearchSparseRetriever implements SparseRetriever {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchSparseRetriever.class);

    private final WebClient openSearchWebClient;
    private final String indexAlias;
    private final int topK;
    private final String tenantField;
    private final String rolesField;

    public OpenSearchSparseRetriever(WebClient openSearchWebClient,
                                     @Value("${chat.opensearch.index:nc_chunks}") String indexAlias,
                                     @Value("${chat.rag.sparse.top-k:8}") int topK,
                                     @Value("${chat.opensearch.tenant-field:tenantId}") String tenantField,
                                     @Value("${chat.opensearch.roles-field:roles}") String rolesField) {
        this.openSearchWebClient = openSearchWebClient;
        this.indexAlias = indexAlias;
        this.topK = topK;
        this.tenantField = tenantField;
        this.rolesField = rolesField;
    }

    @Override
    public List<RetrievedChunk> search(ChatRequest request, String intent) {
        String queryText = Optional.ofNullable(request.turns().isEmpty() ? null : request.turns().getLast().content())
                .filter(text -> !text.isBlank())
                .orElse("help");
        OpenSearchQuery query = buildQuery(request, queryText);
        try {
            OpenSearchResponse response = openSearchWebClient.post()
                    .uri("/{index}/_search", indexAlias)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(query)
                    .retrieve()
                    .bodyToMono(OpenSearchResponse.class)
                    .onErrorResume(throwable -> {
                        log.warn("OpenSearch query failed: {}", throwable.getMessage());
                        return Mono.just(new OpenSearchResponse(Collections.emptyList()));
                    })
                    .block();
            if (response == null) {
                return Collections.emptyList();
            }
            return response.toChunks();
        } catch (Exception e) {
            log.warn("Failed to query OpenSearch", e);
            return Collections.emptyList();
        }
    }

    private OpenSearchQuery buildQuery(ChatRequest request, String queryText) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of(tenantField, request.tenantId())));
        if (request.context() != null && request.context().roles() != null && !request.context().roles().isEmpty()) {
            filters.add(Map.of("terms", Map.of(rolesField, request.context().roles())));
        }
        Map<String, Object> match = Map.of("match", Map.of("text", Map.of("query", queryText)));
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filters);
        bool.put("must", List.of(match));
        Map<String, Object> query = Map.of("bool", bool);
        return new OpenSearchQuery(topK, query, List.of("docId", "title", "page", "text"));
    }

    private record OpenSearchQuery(int size, Map<String, Object> query, List<String> _source) {}

    private record OpenSearchResponse(List<Hit> hits) {
        List<RetrievedChunk> toChunks() {
            if (hits == null) {
                return Collections.emptyList();
            }
            return hits.stream().map(Hit::toChunk).toList();
        }
    }

    private record Hit(double score, Source _source) {
        RetrievedChunk toChunk() {
            if (_source == null) {
                return new RetrievedChunk("", "", 0, "", score, "opensearch");
            }
            return new RetrievedChunk(_source.docId(), _source.title(), _source.page(), _source.text(), score, "opensearch");
        }
    }

    private record Source(String docId, String title, int page, String text) {}
}
