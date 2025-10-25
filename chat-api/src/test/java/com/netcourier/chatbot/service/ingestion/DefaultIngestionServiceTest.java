package com.netcourier.chatbot.service.ingestion;

import com.netcourier.chatbot.model.IngestUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    private DefaultIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        TextChunker chunker = (title, text) -> List.of("first chunk", "second chunk");
        ingestionService = new DefaultIngestionService(textExtractor, chunker, embeddingsClient, vectorStoreClient, searchIndexClient, "CP,BO");
    }

    @Test
    void ingestDocumentShouldPersistChunksAndReturnSummary() {
        when(textExtractor.extract(anyString(), any())).thenReturn(new DocumentTextExtractor.ExtractedDocument("Doc Title", "body"));
        when(embeddingsClient.embed(anyList())).thenReturn(new EmbeddingsClient.EmbeddingBatch(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)), "model", 2));

        IngestUploadResponse response = ingestionService.ingestDocument(new IngestDocumentCommand("TENANT", null, "file.pdf", "data".getBytes(StandardCharsets.UTF_8), List.of("cp")));

        assertThat(response.tenantId()).isEqualTo("TENANT");
        assertThat(response.chunks()).isEqualTo(2);

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
        ingestionService = new DefaultIngestionService(textExtractor, singleChunker, embeddingsClient, vectorStoreClient, searchIndexClient, "CP,BO");
        when(embeddingsClient.embed(anyList())).thenReturn(new EmbeddingsClient.EmbeddingBatch(List.of(List.of(0.5, 0.6)), "model", 2));

        ingestionService.ingestText(new IngestTextCommand("TENANT", "Doc", "text body", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddedChunk>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStoreClient).upsert(eq("TENANT"), anyString(), captor.capture());
        List<EmbeddedChunk> chunks = captor.getValue();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().roles()).containsExactly("BO", "CP");
    }
}
