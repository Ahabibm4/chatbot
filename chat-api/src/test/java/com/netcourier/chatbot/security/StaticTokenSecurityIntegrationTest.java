package com.netcourier.chatbot.security;

import com.netcourier.chatbot.controller.IngestionController;
import com.netcourier.chatbot.model.IngestTextRequest;
import com.netcourier.chatbot.model.IngestUploadResponse;
import com.netcourier.chatbot.service.ingestion.DocumentMetadata;
import com.netcourier.chatbot.service.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

@WebFluxTest(controllers = IngestionController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
@TestPropertySource(properties = "chat.security.static-token=test-token")
class StaticTokenSecurityIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IngestionService ingestionService;

    @Test
    void rejectsJsonIngestionWithoutToken() {
        webTestClient.post()
                .uri("/api/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sampleTextRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsJsonIngestionWithWrongToken() {
        webTestClient.post()
                .uri("/api/ingest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sampleTextRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void allowsJsonIngestionWithConfiguredToken() {
        Mockito.when(ingestionService.ingestText(any()))
                .thenReturn(sampleResponse());

        webTestClient.post()
                .uri("/api/ingest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sampleTextRequest())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("ACME");
    }

    @Test
    void rejectsAdminIngestionWithoutToken() {
        webTestClient.post()
                .uri("/admin/ingest/upload")
                .contentType(MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(sampleMultipart()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void allowsAdminIngestionWithConfiguredToken() {
        Mockito.when(ingestionService.ingestDocument(any()))
                .thenReturn(sampleResponse());

        webTestClient.post()
                .uri("/admin/ingest/upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(sampleMultipart()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("ACME");
    }

    private IngestTextRequest sampleTextRequest() {
        return new IngestTextRequest("ACME", "Doc", "Hello world", List.of("CP"), null, null, null);
    }

    private IngestUploadResponse sampleResponse() {
        return new IngestUploadResponse("ACME", "doc-123", 1, 1, false, DocumentMetadata.empty());
    }

    private MultiValueMap<String, Object> sampleMultipart() {
        ByteArrayResource file = new ByteArrayResource("sample".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "sample.txt";
            }
        };

        LinkedMultiValueMap<String, Object> data = new LinkedMultiValueMap<>();
        data.add("tenantId", "ACME");
        data.add("title", "Doc");
        data.add("file", file);
        return data;
    }
}
