package com.netcourier.chatbot.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OpenSearchIndexClient implements SearchIndexClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexClient.class);

    private final WebClient openSearchWebClient;
    private final String indexAlias;

    public OpenSearchIndexClient(WebClient openSearchWebClient,
                                 @Value("${chat.opensearch.index:nc_chunks}") String indexAlias) {
        this.openSearchWebClient = openSearchWebClient;
        this.indexAlias = indexAlias;
    }

    @Override
    public void index(String tenantId, String docId, List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (EmbeddedChunk chunk : chunks) {
            try {
                openSearchWebClient.put()
                        .uri("/{index}/_doc/{id}", indexAlias, chunk.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new SearchDocument(tenantId, docId, chunk.id(), chunk.title(), chunk.page(), chunk.text(), chunk.roles(), chunk.metadata().asIndexPayload()))
                        .retrieve()
                        .bodyToMono(Void.class)
                        .onErrorResume(throwable -> {
                            log.error("Failed to index chunk {} in OpenSearch: {}", chunk.id(), throwable.getMessage());
                            return Mono.error(new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to index chunk in OpenSearch", throwable));
                        })
                        .block();
            } catch (IngestionException ex) {
                throw ex;
            } catch (Exception e) {
                throw new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to index chunk in OpenSearch", e);
            }
        }
    }

    private record SearchDocument(String tenantId,
                                  String docId,
                                  String chunkId,
                                  String title,
                                  int page,
                                  String text,
                                  List<String> roles,
                                  java.util.Map<String, Object> metadata) {}
}
