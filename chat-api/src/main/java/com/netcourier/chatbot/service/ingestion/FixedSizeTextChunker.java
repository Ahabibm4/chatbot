package com.netcourier.chatbot.service.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FixedSizeTextChunker implements TextChunker {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeTextChunker(@Value("${chat.ingest.chunk-size:800}") int chunkSize,
                                @Value("${chat.ingest.overlap:200}") int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<String> chunk(String title, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = Arrays.stream(text.split("\r?\n\s*\r?\n"))
                .map(String::trim)
                .filter(paragraph -> !paragraph.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
        if (paragraphs.isEmpty()) {
            paragraphs = Arrays.stream(text.split("\r?\n"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (paragraphs.isEmpty()) {
            return List.of(text.trim());
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (current.length() + paragraph.length() + 1 > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                if (overlap > 0 && !chunks.isEmpty()) {
                    String previous = chunks.getLast();
                    String tail = tail(previous, overlap);
                    if (!tail.isBlank()) {
                        current.append(tail).append("\n");
                    }
                }
            }
            current.append(paragraph).append("\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private String tail(String source, int maxLength) {
        if (source.length() <= maxLength) {
            return source;
        }
        return source.substring(source.length() - maxLength);
    }
}
