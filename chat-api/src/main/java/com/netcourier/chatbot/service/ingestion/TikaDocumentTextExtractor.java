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
            if (text.isBlank()) {
                throw new IngestionException(HttpStatus.BAD_REQUEST, "Uploaded file does not contain extractable text");
            }
            String title = Optional.ofNullable(metadata.get(Metadata.TITLE))
                    .filter(value -> !value.isBlank())
                    .orElseGet(() -> defaultTitle(filename));
            return new ExtractedDocument(title, text);
        } catch (IngestionException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to extract text from document {}", filename, e);
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to extract document text", e);
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
