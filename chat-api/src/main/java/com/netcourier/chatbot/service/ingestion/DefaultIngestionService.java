package com.netcourier.chatbot.service.ingestion;

import com.netcourier.chatbot.model.IngestUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DefaultIngestionService implements IngestionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultIngestionService.class);

    private final DocumentTextExtractor textExtractor;
    private final TextChunker textChunker;
    private final EmbeddingsClient embeddingsClient;
    private final VectorStoreClient vectorStoreClient;
    private final SearchIndexClient searchIndexClient;
    private final List<String> defaultRoles;

    public DefaultIngestionService(DocumentTextExtractor textExtractor,
                                   TextChunker textChunker,
                                   EmbeddingsClient embeddingsClient,
                                   VectorStoreClient vectorStoreClient,
                                   SearchIndexClient searchIndexClient,
                                   @Value("${chat.ingest.default-roles:CP,BO}") String defaultRoles) {
        this.textExtractor = textExtractor;
        this.textChunker = textChunker;
        this.embeddingsClient = embeddingsClient;
        this.vectorStoreClient = vectorStoreClient;
        this.searchIndexClient = searchIndexClient;
        this.defaultRoles = parseRoles(defaultRoles);
    }

    @Override
    public IngestUploadResponse ingestDocument(IngestDocumentCommand command) {
        if (command == null || command.bytes() == null || command.bytes().length == 0) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        try (InputStream inputStream = new ByteArrayInputStream(command.bytes())) {
            DocumentTextExtractor.ExtractedDocument extracted = textExtractor.extract(command.filename(), inputStream);
            String title = resolveTitle(command.title(), extracted.title());
            return ingestInternal(command.tenantId(), title, extracted.text(), command.roles());
        } catch (IngestionException ex) {
            throw ex;
        } catch (Exception e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to ingest document", e);
        }
    }

    @Override
    public IngestUploadResponse ingestText(IngestTextCommand command) {
        if (command == null || command.text() == null || command.text().isBlank()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Text payload must not be empty");
        }
        String title = resolveTitle(command.title(), "Document");
        return ingestInternal(command.tenantId(), title, command.text(), command.roles());
    }

    private IngestUploadResponse ingestInternal(String tenantId, String title, String text, List<String> rolesInput) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Tenant ID is required");
        }
        List<String> chunks = textChunker.chunk(title, text);
        if (chunks.isEmpty()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "No content chunks were produced for ingestion");
        }
        EmbeddingsClient.EmbeddingBatch embeddings = embeddingsClient.embed(chunks);
        if (embeddings.vectors().size() != chunks.size()) {
            throw new IngestionException(HttpStatus.BAD_GATEWAY, "Embeddings response size did not match chunks");
        }
        String docId = UUID.randomUUID().toString();
        List<String> roles = rolesOrDefault(rolesInput);
        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "-" + (i + 1);
            embeddedChunks.add(new EmbeddedChunk(chunkId, title, i + 1, normaliseWhitespace(chunks.get(i)), roles, embeddings.vectors().get(i)));
        }
        vectorStoreClient.upsert(tenantId, docId, embeddedChunks);
        searchIndexClient.index(tenantId, docId, embeddedChunks);
        log.info("Ingested document {} for tenant {} with {} chunks", docId, tenantId, embeddedChunks.size());
        return new IngestUploadResponse(tenantId, docId, embeddedChunks.size());
    }

    private List<String> rolesOrDefault(List<String> rolesInput) {
        if (rolesInput == null || rolesInput.isEmpty()) {
            return defaultRoles;
        }
        Set<String> cleaned = new HashSet<>();
        for (String role : rolesInput) {
            if (role != null && !role.isBlank()) {
                cleaned.add(role.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (cleaned.isEmpty()) {
            return defaultRoles;
        }
        return cleaned.stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    private List<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of("CP", "BO");
        }
        return java.util.Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    private String resolveTitle(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? "Document" : fallback.trim();
    }

    private String normaliseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\s+", " ").trim();
    }
}
