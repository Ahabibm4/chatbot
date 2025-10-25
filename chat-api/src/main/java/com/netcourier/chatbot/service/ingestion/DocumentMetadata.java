package com.netcourier.chatbot.service.ingestion;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record DocumentMetadata(
        String title,
        String author,
        OffsetDateTime createdAt,
        String contentType,
        String source,
        Map<String, String> attributes
) {

    public static DocumentMetadata empty() {
        return new DocumentMetadata(null, null, null, null, null, Map.of());
    }

    public DocumentMetadata withFallbackTitle(String fallback) {
        return withTitle(title != null && !title.isBlank() ? title : fallback);
    }

    public DocumentMetadata withTitle(String value) {
        return new DocumentMetadata(normalise(value), author, createdAt, contentType, source, attributes);
    }

    public DocumentMetadata withAuthor(String value) {
        return new DocumentMetadata(title, normalise(value), createdAt, contentType, source, attributes);
    }

    public DocumentMetadata withCreatedAt(OffsetDateTime value) {
        return new DocumentMetadata(title, author, value, contentType, source, attributes);
    }

    public DocumentMetadata withContentType(String value) {
        return new DocumentMetadata(title, author, createdAt, normalise(value), source, attributes);
    }

    public DocumentMetadata withSource(String value) {
        return new DocumentMetadata(title, author, createdAt, contentType, normalise(value), attributes);
    }

    public DocumentMetadata withAttributes(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new HashMap<>(attributes == null ? Map.of() : attributes);
        extra.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key, value);
            }
        });
        return new DocumentMetadata(title, author, createdAt, contentType, source, Collections.unmodifiableMap(merged));
    }

    private String normalise(String value) {
        return value == null ? null : value.trim();
    }

    public boolean isMeaningful() {
        return (title != null && !title.isBlank())
                || (author != null && !author.isBlank())
                || createdAt != null
                || (contentType != null && !contentType.isBlank())
                || (source != null && !source.isBlank())
                || !attributes().isEmpty();
    }

    @Override
    public Map<String, String> attributes() {
        return attributes == null ? Map.of() : attributes;
    }

    public DocumentMetadata merge(DocumentMetadata overrides) {
        if (overrides == null) {
            return this;
        }
        return new DocumentMetadata(
                pick(overrides.title, title),
                pick(overrides.author, author),
                overrides.createdAt != null ? overrides.createdAt : createdAt,
                pick(overrides.contentType, contentType),
                pick(overrides.source, source),
                mergeAttributes(overrides.attributes)
        );
    }

    private Map<String, String> mergeAttributes(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return attributes();
        }
        Map<String, String> merged = new HashMap<>(attributes());
        overrides.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key, value);
            }
        });
        return Collections.unmodifiableMap(merged);
    }

    private String pick(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim() : fallback;
    }

    public Map<String, Object> asIndexPayload() {
        Map<String, Object> payload = new HashMap<>();
        if (title != null) {
            payload.put("title", title);
        }
        if (author != null) {
            payload.put("author", author);
        }
        if (createdAt != null) {
            payload.put("createdAt", createdAt.toString());
        }
        if (contentType != null) {
            payload.put("contentType", contentType);
        }
        if (source != null) {
            payload.put("source", source);
        }
        if (!attributes().isEmpty()) {
            payload.put("attributes", attributes());
        }
        return Collections.unmodifiableMap(payload);
    }

    public DocumentMetadata ensureImmutable() {
        return new DocumentMetadata(title, author, createdAt, contentType, source, attributes());
    }

    public boolean sameContent(DocumentMetadata other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(title, other.title)
                && Objects.equals(author, other.author)
                && Objects.equals(createdAt, other.createdAt)
                && Objects.equals(contentType, other.contentType)
                && Objects.equals(source, other.source)
                && Objects.equals(attributes(), other.attributes());
    }
}
