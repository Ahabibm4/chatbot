package com.netcourier.chatbot.service.ingestion;

import com.netcourier.chatbot.model.IngestUploadResponse;
import com.netcourier.chatbot.persistence.entity.DocumentIngestionEntity;
import com.netcourier.chatbot.persistence.repository.DocumentIngestionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultIngestionServiceTest {

    @Mock
    private DocumentTextExtractor textExtractor;

    @Mock
    private EmbeddingsClient embeddingsClient;

    @Mock
    private VectorStoreClient vectorStoreClient;

    @Mock
    private SearchIndexClient searchIndexClient;

    @Mock
    private DocumentIngestionRepository ingestionRepository;

    private SimpleMeterRegistry meterRegistry;

    private DefaultIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        TextChunker chunker = (title, text) -> List.of("first chunk", "second chunk");
        meterRegistry = new SimpleMeterRegistry();
        ingestionService = new DefaultIngestionService(textExtractor, chunker, embeddingsClient, vectorStoreClient, searchIndexClient, ingestionRepository, meterRegistry, "CP,BO");
        when(ingestionRepository.findTopByTenantIdAndExternalIdOrderByVersionDesc(anyString(), anyString())).thenReturn(Optional.empty());
        when(ingestionRepository.findTopByTenantIdAndContentHashOrderByVersionDesc(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void ingestDocumentShouldPersistChunksAndReturnSummary() {
        DocumentMetadata metadata = DocumentMetadata.empty().withTitle("Doc Title");
        when(textExtractor.extract(anyString(), any())).thenReturn(new DocumentTextExtractor.ExtractedDocument(metadata, "body"));
        when(embeddingsClient.embed(anyList())).thenReturn(new EmbeddingsClient.EmbeddingBatch(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)), "model", 2));

        IngestUploadResponse response = ingestionService.ingestDocument(new IngestDocumentCommand("TENANT", null, "file.pdf", "data".getBytes(StandardCharsets.UTF_8), List.of("cp"), null, DocumentMetadata.empty()));

        assertThat(response.tenantId()).isEqualTo("TENANT");
        assertThat(response.chunks()).isEqualTo(2);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.deduplicated()).isFalse();
        assertThat(response.metadata().title()).isEqualTo("Doc Title");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddedChunk>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStoreClient).upsert(eq("TENANT"), anyString(), captor.capture());
        verify(searchIndexClient).index(eq("TENANT"), anyString(), anyList());

        List<EmbeddedChunk> captured = captor.getValue();
        assertThat(captured).hasSize(2);
        assertThat(captured.getFirst().roles()).containsExactly("CP");
        assertThat(captured.getFirst().text()).isEqualTo("first chunk");
    }

    @Test
    void ingestTextWithoutRolesUsesDefaults() {
        TextChunker singleChunker = (title, text) -> List.of("only chunk");
        meterRegistry = new SimpleMeterRegistry();
        ingestionService = new DefaultIngestionService(textExtractor, singleChunker, embeddingsClient, vectorStoreClient, searchIndexClient, ingestionRepository, meterRegistry, "CP,BO");
        when(ingestionRepository.findTopByTenantIdAndExternalIdOrderByVersionDesc(anyString(), anyString())).thenReturn(Optional.empty());
        when(ingestionRepository.findTopByTenantIdAndContentHashOrderByVersionDesc(anyString(), anyString())).thenReturn(Optional.empty());
        when(embeddingsClient.embed(anyList())).thenReturn(new EmbeddingsClient.EmbeddingBatch(List.of(List.of(0.5, 0.6)), "model", 2));

        ingestionService.ingestText(new IngestTextCommand("TENANT", "Doc", "text body", null, null, DocumentMetadata.empty()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddedChunk>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStoreClient).upsert(eq("TENANT"), anyString(), captor.capture());
        List<EmbeddedChunk> chunks = captor.getValue();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().roles()).containsExactly("BO", "CP");
    }

    @Test
    void ingestDocumentSkipsDuplicatesUsingHash() {
        DocumentMetadata metadata = DocumentMetadata.empty().withTitle("Doc Title");
        when(textExtractor.extract(anyString(), any())).thenReturn(new DocumentTextExtractor.ExtractedDocument(metadata, "duplicate body"));
        DocumentIngestionEntity existing = new DocumentIngestionEntity();
        existing.setDocumentId("DOC-1");
        existing.setChunks(2);
        existing.setVersion(4);
        existing.setContentHash(hash("duplicate body"));
        when(ingestionRepository.findTopByTenantIdAndContentHashOrderByVersionDesc(eq("TENANT"), eq(existing.getContentHash())))
                .thenReturn(Optional.of(existing));

        IngestUploadResponse response = ingestionService.ingestDocument(new IngestDocumentCommand("TENANT", null, "file.pdf", "duplicate body".getBytes(StandardCharsets.UTF_8), List.of(), null, DocumentMetadata.empty()));

        assertThat(response.deduplicated()).isTrue();
        assertThat(response.docId()).isEqualTo("DOC-1");
        assertThat(response.version()).isEqualTo(4);
        verifyNoInteractions(vectorStoreClient);
        verifyNoInteractions(searchIndexClient);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
