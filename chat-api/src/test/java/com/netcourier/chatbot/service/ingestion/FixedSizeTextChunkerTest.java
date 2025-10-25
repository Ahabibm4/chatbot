package com.netcourier.chatbot.service.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixedSizeTextChunkerTest {

    @Test
    void chunkSplitsLongTextAndAppliesOverlap() {
        FixedSizeTextChunker chunker = new FixedSizeTextChunker(50, 10);
        String text = "Paragraph one providing context.\n\nParagraph two contains more detail about the process and should cause a split.";

        List<String> chunks = chunker.chunk("Doc", text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.getFirst().length()).isLessThanOrEqualTo(50);
        assertThat(chunks.getLast()).contains("split");
    }
}
