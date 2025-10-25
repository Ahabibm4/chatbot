package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TemplateLlmClient implements LlmClient {

    @Override
    public LlmResponse generate(LlmRequest request) {
        String answer = synthesizeAnswer(request.userPrompt(), request.context(), request.classification());
        return LlmResponse.allow(answer);
    }

    private String synthesizeAnswer(String userPrompt, List<RetrievedChunk> context, String classification) {
        StringBuilder builder = new StringBuilder();
        builder.append("Intent classification: ").append(classification).append(".\n\n");
        builder.append(userPrompt == null ? "" : userPrompt.trim()).append("\n\n");
        if (context == null || context.isEmpty()) {
            builder.append("No contextual documents were retrieved. Provide a polite fallback and suggest escalating to a human agent.");
        } else {
            builder.append("Key findings:\n");
            for (int i = 0; i < context.size(); i++) {
                RetrievedChunk chunk = context.get(i);
                builder.append(i + 1)
                        .append(". ")
                        .append(normalise(chunk.text()))
                        .append(" [")
                        .append(chunk.title())
                        .append(" · p.")
                        .append(chunk.page())
                        .append("]\n");
            }
            builder.append("\nProvide the final answer referencing the citations as footnotes.");
        }
        return builder.toString();
    }

    private String normalise(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\s+", " ").trim();
        if (trimmed.length() <= 240) {
            return trimmed;
        }
        return trimmed.substring(0, 237) + "...";
    }
}
