package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.Citation;
import com.netcourier.chatbot.model.RetrievedChunk;
import com.netcourier.chatbot.model.ToolCallResult;
import com.netcourier.chatbot.model.WorkflowResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class GuardedOrchestrationService implements OrchestrationService {

    private static final String SYSTEM_PROMPT = "You are the NetCourier enterprise assistant. Provide concise, factual answers and cite sources using the format [Title · p.X]. Never fabricate information.";

    private final LlmClient llmClient;
    private final QueryClassifier classifier;
    private final TokenBudgetGuard tokenGuard;
    private final int maxCitations;

    public GuardedOrchestrationService(LlmClient llmClient,
                                       @Value("${chat.orchestration.max-context-tokens:2048}") int maxContextTokens,
                                       @Value("${chat.orchestration.max-citations:5}") int maxCitations) {
        this.llmClient = llmClient;
        this.classifier = new QueryClassifier();
        this.tokenGuard = new TokenBudgetGuard(maxContextTokens);
        this.maxCitations = Math.max(1, maxCitations);
    }

    @Override
    public GuardedResponse orchestrate(ChatRequest request,
                                       String intent,
                                       List<RetrievedChunk> chunks,
                                       WorkflowResult workflowResult) {
        OrchestrationInputs inputs = buildInputs(request, intent, chunks, workflowResult);

        LlmResponse llmResponse = llmClient.generate(inputs.llmRequest());
        String guardrailAction = inputs.truncated() && !Objects.equals(llmResponse.guardrailAction(), "BLOCKED")
                ? "TRUNCATED"
                : llmResponse.guardrailAction();
        return new GuardedResponse(llmResponse.answer(), inputs.citations(), guardrailAction);
    }

    @Override
    public Flux<GuardedStreamSegment> orchestrateStream(ChatRequest request,
                                                        String intent,
                                                        List<RetrievedChunk> chunks,
                                                        WorkflowResult workflowResult) {
        OrchestrationInputs inputs = buildInputs(request, intent, chunks, workflowResult);
        boolean truncated = inputs.truncated();
        List<Citation> citations = inputs.citations();

        return llmClient.stream(inputs.llmRequest())
                .map(event -> {
                    if (event.type() == LlmStreamEvent.Type.PARTIAL) {
                        return GuardedStreamSegment.partial(event.text());
                    }
                    String guardrailAction = event.guardrailAction();
                    if (truncated && !Objects.equals(guardrailAction, "BLOCKED")) {
                        guardrailAction = "TRUNCATED";
                    }
                    return GuardedStreamSegment.finalSegment(event.text(), citations, guardrailAction);
                });
    }

    private OrchestrationInputs buildInputs(ChatRequest request,
                                            String intent,
                                            List<RetrievedChunk> chunks,
                                            WorkflowResult workflowResult) {
        QueryClassifier.QueryType type = classifier.classify(request, intent, chunks, workflowResult);
        List<RetrievedChunk> sorted = chunks == null ? List.of() : chunks.stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .toList();
        TokenBudgetGuard.GuardedChunks guardedChunks = tokenGuard.enforce(sorted);
        Map<String, Object> workflowContext = buildWorkflowContext(workflowResult);
        String userPrompt = latestUserUtterance(request.turns());

        LlmRequest llmRequest = new LlmRequest(
                SYSTEM_PROMPT,
                augmentUserPrompt(userPrompt, workflowResult.responseMessage()),
                guardedChunks.chunks(),
                workflowContext,
                type.name()
        );

        List<Citation> citations = toCitations(guardedChunks.chunks());
        return new OrchestrationInputs(llmRequest, citations, guardedChunks.truncated());
    }

    private Map<String, Object> buildWorkflowContext(WorkflowResult workflowResult) {
        Map<String, Object> context = new HashMap<>();
        context.put("workflowId", workflowResult.workflowId());
        context.put("state", workflowResult.state());
        context.put("slots", workflowResult.slots());
        workflowResult.toolToInvoke().ifPresent(tool -> context.put("toolName", tool));
        ToolCallResult toolResult = workflowResult.toolResult();
        if (toolResult != null) {
            Map<String, Object> tool = new HashMap<>();
            tool.put("status", toolResult.status());
            tool.put("detail", toolResult.detail());
            context.put("toolResult", tool);
        }
        return context;
    }

    private String latestUserUtterance(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatTurn turn = turns.get(i);
            if (turn.role() == ChatMessageRole.USER && turn.content() != null) {
                return turn.content();
            }
        }
        return turns.getLast().content();
    }

    private String augmentUserPrompt(String userPrompt, String workflowGuidance) {
        StringBuilder builder = new StringBuilder(userPrompt == null ? "" : userPrompt.trim());
        if (workflowGuidance != null && !workflowGuidance.isBlank()) {
            builder.append("\n\nWorkflow guidance: ").append(workflowGuidance.trim());
        }
        builder.append("\n\nReturn a final answer with numbered recommendations if multiple actions are required.");
        return builder.toString();
    }

    private List<Citation> toCitations(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Citation> citations = new ArrayList<>();
        int limit = Math.min(maxCitations, chunks.size());
        for (int i = 0; i < limit; i++) {
            RetrievedChunk chunk = chunks.get(i);
            citations.add(new Citation(
                    chunk.docId(),
                    chunk.title(),
                    chunk.page(),
                    createSnippet(chunk.text()),
                    formatReference(chunk)
            ));
        }
        return List.copyOf(citations);
    }

    private String createSnippet(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\s+", " ").trim();
        if (trimmed.length() <= 220) {
            return trimmed;
        }
        return trimmed.substring(0, 217) + "...";
    }

    private String formatReference(RetrievedChunk chunk) {
        String title = chunk.title() == null ? "Document" : chunk.title();
        return String.format(Locale.ROOT, "%s · p.%d", title, chunk.page());
    }
}

private record OrchestrationInputs(LlmRequest llmRequest, List<Citation> citations, boolean truncated) {
}
