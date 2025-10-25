package com.netcourier.chatbot.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class WebClientEmbeddingsClient implements EmbeddingsClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientEmbeddingsClient.class);

    private final WebClient embeddingsWebClient;

    public WebClientEmbeddingsClient(WebClient embeddingsWebClient) {
        this.embeddingsWebClient = embeddingsWebClient;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "No text chunks provided for embedding");
        }
        try {
            EmbedResponse response = embeddingsWebClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new EmbedRequest(texts))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .onErrorResume(throwable -> {
                        log.error("Embeddings service call failed: {}", throwable.getMessage());
                        return Mono.error(new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to compute embeddings", throwable));
                    })
                    .block();
            if (response == null || response.vectors() == null || response.vectors().isEmpty()) {
                throw new IngestionException(HttpStatus.BAD_GATEWAY, "Embeddings service returned no vectors");
            }
            return new EmbeddingBatch(response.vectors(), response.model(), response.dimensions());
        } catch (IngestionException ex) {
            throw ex;
        } catch (Exception e) {
            throw new IngestionException(HttpStatus.BAD_GATEWAY, "Failed to compute embeddings", e);
        }
    }

    private record EmbedRequest(List<String> texts) {}

    private record EmbedResponse(List<List<Double>> vectors, String model, int dimensions) {}
}
