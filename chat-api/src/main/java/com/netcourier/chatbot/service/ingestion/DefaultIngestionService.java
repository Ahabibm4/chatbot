package com.netcourier.chatbot.service.ingestion;

import com.netcourier.chatbot.model.IngestUploadResponse;
import com.netcourier.chatbot.persistence.entity.DocumentIngestionEntity;
import com.netcourier.chatbot.persistence.repository.DocumentIngestionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
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
    private final DocumentIngestionRepository ingestionRepository;
    private final MeterRegistry meterRegistry;
    private final Counter ingestionCounter;
    private final Counter dedupCounter;
    private final Timer ingestionTimer;
    private final List<String> defaultRoles;

    public DefaultIngestionService(DocumentTextExtractor textExtractor,
                                   TextChunker textChunker,
                                   EmbeddingsClient embeddingsClient,
                                   VectorStoreClient vectorStoreClient,
                                   SearchIndexClient searchIndexClient,
                                   DocumentIngestionRepository ingestionRepository,
                                   MeterRegistry meterRegistry,
                                   @Value("${chat.ingest.default-roles:CP,BO}") String defaultRoles) {
        this.textExtractor = textExtractor;
        this.textChunker = textChunker;
        this.embeddingsClient = embeddingsClient;
        this.vectorStoreClient = vectorStoreClient;
        this.searchIndexClient = searchIndexClient;
        this.ingestionRepository = ingestionRepository;
        this.meterRegistry = meterRegistry;
        this.ingestionCounter = meterRegistry.counter("chat.ingest.events", "outcome", "accepted");
        this.dedupCounter = meterRegistry.counter("chat.ingest.events", "outcome", "deduplicated");
        this.ingestionTimer = meterRegistry.timer("chat.ingest.duration");
        this.defaultRoles = parseRoles(defaultRoles);
    }

    @Override
    public IngestUploadResponse ingestDocument(IngestDocumentCommand command) {
        if (command == null || command.bytes() == null || command.bytes().length == 0) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        try (InputStream inputStream = new ByteArrayInputStream(command.bytes())) {
            DocumentTextExtractor.ExtractedDocument extracted = textExtractor.extract(command.filename(), inputStream);
            DocumentMetadata metadata = mergeMetadata(extracted.metadata(), command.metadata())
                    .withFallbackTitle(resolveTitle(command.title(), extracted.metadata().title()))
                    .withContentType(extracted.metadata().contentType());
            return ingestInternal(command.tenantId(), metadata, extracted.text(), command.roles(), command.externalId());
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
        DocumentMetadata metadata = mergeMetadata(DocumentMetadata.empty(), command.metadata())
                .withFallbackTitle(resolveTitle(command.title(), "Document"));
        return ingestInternal(command.tenantId(), metadata, command.text(), command.roles(), command.externalId());
    }

    private IngestUploadResponse ingestInternal(String tenantId,
                                                DocumentMetadata metadata,
                                                String text,
                                                List<String> rolesInput,
                                                String externalId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Tenant ID is required");
        }
        DocumentMetadata hydrated = hydrateMetadata(metadata);
        String normalised = normaliseWhitespace(text);
        if (normalised.isBlank()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "No content was extracted from the document");
        }
        String hash = sha256(normalised);
        List<String> roles = rolesOrDefault(rolesInput);

        DocumentIngestionEntity existingByExternal = externalId == null ? null : ingestionRepository
                .findTopByTenantIdAndExternalIdOrderByVersionDesc(tenantId, externalId)
                .orElse(null);
        DocumentIngestionEntity existingByHash = ingestionRepository
                .findTopByTenantIdAndContentHashOrderByVersionDesc(tenantId, hash)
                .orElse(null);

        DocumentIngestionEntity baseline = existingByExternal != null ? existingByExternal : existingByHash;
        boolean deduplicated = baseline != null && hash.equals(baseline.getContentHash());
        if (deduplicated) {
            dedupCounter.increment();
            log.info("Skipped re-embedding of document {} for tenant {} due to matching content hash", baseline.getDocumentId(), tenantId);
            return new IngestUploadResponse(tenantId, baseline.getDocumentId(), baseline.getChunks(), baseline.getVersion(), true, hydrated);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            IngestUploadResponse response = ingestFreshDocument(tenantId, hydrated, normalised, roles, externalId, hash, baseline);
            ingestionCounter.increment();
            return response;
        } finally {
            sample.stop(ingestionTimer);
        }
    }

    private IngestUploadResponse ingestFreshDocument(String tenantId,
                                                     DocumentMetadata metadata,
                                                     String text,
                                                     List<String> roles,
                                                     String externalId,
                                                     String contentHash,
                                                     DocumentIngestionEntity baseline) {
        List<String> chunks = textChunker.chunk(metadata.title(), text);
        if (chunks.isEmpty()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "No content chunks were produced for ingestion");
        }
        EmbeddingsClient.EmbeddingBatch embeddings = embeddingsClient.embed(chunks);
        if (embeddings.vectors().size() != chunks.size()) {
            throw new IngestionException(HttpStatus.BAD_GATEWAY, "Embeddings response size did not match chunks");
        }
        String docId = baseline != null ? baseline.getDocumentId() : UUID.randomUUID().toString();
        int version = baseline == null ? 1 : baseline.getVersion() + 1;

        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "-" + (i + 1);
            embeddedChunks.add(new EmbeddedChunk(chunkId, metadata.title(), i + 1, normaliseWhitespace(chunks.get(i)), roles, metadata, embeddings.vectors().get(i)));
        }
        vectorStoreClient.upsert(tenantId, docId, embeddedChunks);
        searchIndexClient.index(tenantId, docId, embeddedChunks);
        persistSnapshot(tenantId, docId, externalId, version, contentHash, roles, metadata, embeddedChunks.size());
        log.info("Ingested document {} for tenant {} with {} chunks (version {})", docId, tenantId, embeddedChunks.size(), version);
        return new IngestUploadResponse(tenantId, docId, embeddedChunks.size(), version, false, metadata);
    }

    private void persistSnapshot(String tenantId,
                                 String documentId,
                                 String externalId,
                                 int version,
                                 String hash,
                                 List<String> roles,
                                 DocumentMetadata metadata,
                                 int chunks) {
        DocumentIngestionEntity entity = new DocumentIngestionEntity();
        entity.setTenantId(tenantId);
        entity.setDocumentId(documentId);
        entity.setExternalId(externalId);
        entity.setVersion(version);
        entity.setContentHash(hash);
        entity.setTitle(metadata.title());
        entity.setAuthor(metadata.author());
        entity.setContentType(metadata.contentType());
        entity.setSource(metadata.source());
        entity.setCreatedAt(metadata.createdAt());
        entity.setChunks(chunks);
        entity.setIngestedAt(OffsetDateTime.now());
        entity.setRoles(new java.util.HashSet<>(roles));
        entity.setAttributes(metadata.attributes());
        ingestionRepository.save(entity);
    }

    private DocumentMetadata hydrateMetadata(DocumentMetadata metadata) {
        if (metadata == null || !metadata.isMeaningful()) {
            return DocumentMetadata.empty().withFallbackTitle("Document");
        }
        return metadata.ensureImmutable();
    }

    private DocumentMetadata mergeMetadata(DocumentMetadata extracted, DocumentMetadata provided) {
        DocumentMetadata base = extracted == null ? DocumentMetadata.empty() : extracted.ensureImmutable();
        return provided == null ? base : base.merge(provided.ensureImmutable());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to hash document", e);
        }
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
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
