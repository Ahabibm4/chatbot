package com.netcourier.chatbot.service.ingestion;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

@Component
public class TesseractOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);

    private final ITesseract tesseract;
    private final boolean enabled;

    public TesseractOcrService(@Value("${chat.ingest.ocr.enabled:true}") boolean enabled,
                               @Value("${chat.ingest.ocr.language:eng}") String language,
                               @Value("${chat.ingest.ocr.datapath:}") String datapath) {
        this.enabled = enabled;
        if (enabled) {
            Tesseract engine = new Tesseract();
            if (datapath != null && !datapath.isBlank()) {
                engine.setDatapath(datapath);
            }
            if (language != null && !language.isBlank()) {
                engine.setLanguage(language);
            }
            this.tesseract = engine;
        } else {
            this.tesseract = null;
        }
    }

    @Override
    public String extractText(String filename, InputStream inputStream) {
        if (!enabled) {
            return "";
        }
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("nc-ocr-", normaliseExtension(filename)).toFile();
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                inputStream.transferTo(outputStream);
            }
            String result = tesseract.doOCR(tempFile);
            return result == null ? "" : result.trim();
        } catch (IOException e) {
            log.warn("Failed to persist temporary file for OCR", e);
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create temp file for OCR", e);
        } catch (Exception e) {
            log.warn("OCR extraction failed for {}", filename, e);
            return "";
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (IOException ex) {
                    log.debug("Failed to delete temp file {}", tempFile.getAbsolutePath(), ex);
                }
            }
        }
    }

    private String normaliseExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return ".ocr";
        }
        int idx = filename.lastIndexOf('.');
        String ext = idx > -1 ? filename.substring(idx) : ".ocr";
        return ext.toLowerCase(Locale.ROOT);
    }
}
