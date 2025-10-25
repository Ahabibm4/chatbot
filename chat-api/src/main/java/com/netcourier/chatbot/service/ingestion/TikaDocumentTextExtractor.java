package com.netcourier.chatbot.service.ingestion;

import org.apache.commons.io.FilenameUtils;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentTextExtractor.class);

    @Override
    public ExtractedDocument extract(String filename, InputStream inputStream) {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(Metadata.RESOURCE_NAME_KEY, filename);
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, new ParseContext());
            String text = Optional.ofNullable(handler.toString())
                    .map(content -> new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .map(String::trim)
                    .orElse("");

            DocumentMetadata docMetadata = buildMetadata(metadata, filename);
            return new ExtractedDocument(docMetadata, text);
        } catch (Exception e) {
            log.error("Failed to extract text from document {}", filename, e);
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to extract document text", e);
        }
    }

    private DocumentMetadata buildMetadata(Metadata metadata, String filename) {
        String title = Optional.ofNullable(metadata.get(Metadata.TITLE))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> defaultTitle(filename));
        DocumentMetadata enriched = DocumentMetadata.empty()
                .withTitle(title)
                .withAuthor(metadata.get(Metadata.AUTHOR))
                .withContentType(metadata.get(Metadata.CONTENT_TYPE))
                .withAttributes(extractAttributes(metadata));
        OffsetDateTime created = parseDate(metadata.get(Metadata.CREATION_DATE));
        if (created != null) {
            enriched = enriched.withCreatedAt(created);
        }
        return enriched.ensureImmutable();
    }

    private Map<String, String> extractAttributes(Metadata metadata) {
        Map<String, String> attributes = new HashMap<>();
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) {
                attributes.put(name, value);
            }
        }
        return attributes;
    }

    private OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ex) {
                log.debug("Unable to parse creation date {}", value, ex);
                return null;
            }
        }
    }

    private String defaultTitle(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Document";
        }
        String baseName = FilenameUtils.getBaseName(filename);
        return baseName.isBlank() ? "Document" : baseName;
    }
}
