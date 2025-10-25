package com.netcourier.chatbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

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

    private WebClient baseClient(String baseUrl) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .build();
    }
}
