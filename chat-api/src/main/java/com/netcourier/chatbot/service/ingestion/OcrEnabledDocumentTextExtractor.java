package com.netcourier.chatbot.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
public class OcrEnabledDocumentTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(OcrEnabledDocumentTextExtractor.class);

    private final DocumentTextExtractor delegate;
    private final OcrService ocrService;

    public OcrEnabledDocumentTextExtractor(TikaDocumentTextExtractor delegate, OcrService ocrService) {
        this.delegate = delegate;
        this.ocrService = ocrService;
    }

    @Override
    public ExtractedDocument extract(String filename, InputStream inputStream) {
        byte[] bytes = toByteArray(inputStream);
        ExtractedDocument extracted = tryPrimary(filename, bytes);
        if (extracted != null && extracted.text() != null && !extracted.text().isBlank()) {
            return extracted;
        }
        String ocrText = runOcr(filename, bytes);
        if (ocrText.isBlank()) {
            if (extracted != null) {
                throw new IngestionException(HttpStatus.BAD_REQUEST, "Document text could not be extracted even with OCR");
            }
            throw new IngestionException(HttpStatus.BAD_REQUEST, "Document did not contain extractable text");
        }
        DocumentMetadata metadata = extracted != null ? extracted.metadata() : DocumentMetadata.empty().withFallbackTitle("Document");
        return new ExtractedDocument(metadata.ensureImmutable(), ocrText);
    }

    private ExtractedDocument tryPrimary(String filename, byte[] bytes) {
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            return delegate.extract(filename, stream);
        } catch (IngestionException ex) {
            log.debug("Primary text extraction failed for {}: {}", filename, ex.getMessage());
            return null;
        } catch (IOException e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read document stream", e);
        }
    }

    private String runOcr(String filename, byte[] bytes) {
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            return ocrService.extractText(filename, stream);
        } catch (IOException e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read document stream for OCR", e);
        }
    }

    private byte[] toByteArray(InputStream inputStream) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            inputStream.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded document", e);
        }
    }
}
