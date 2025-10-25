package com.netcourier.chatbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient qdrantWebClient(@Value("${chat.qdrant.base-url:http://localhost:6333}") String baseUrl) {
        return baseClient(baseUrl);
    }

    @Bean
    public WebClient openSearchWebClient(@Value("${chat.opensearch.base-url:http://localhost:9200}") String baseUrl) {
        return baseClient(baseUrl);
    }

    @Bean
    public WebClient netCourierApiClient(@Value("${chat.netcourier.base-url:http://localhost:8085}") String baseUrl) {
        return baseClient(baseUrl);
    }

    @Bean
    public WebClient embeddingsWebClient(@Value("${chat.embeddings.base-url:http://localhost:9000}") String baseUrl) {
        return baseClient(baseUrl);
    }

    @Bean
    public WebClient llmWebClient(@Value("${chat.llm.base-url:http://localhost:1234}") String baseUrl,
                                  @Value("${chat.llm.api-key:}") String apiKey,
                                  @Value("${chat.llm.timeout-seconds:60}") long timeoutSeconds) {
        ExchangeStrategies strategies = exchangeStrategies();
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies);
        if (timeoutSeconds > 0) {
            HttpClient httpClient = HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(timeoutSeconds));
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        builder.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        builder.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return builder.build();
    }

    private WebClient baseClient(String baseUrl) {
        ExchangeStrategies strategies = exchangeStrategies();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .build();
    }

    private ExchangeStrategies exchangeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }
}
