package com.netcourier.chatbot.service.workflow;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DefaultWorkflowEngine implements WorkflowEngine {

    private static final Pattern JOB_PATTERN = Pattern.compile("(NC\\d{6,})");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?i)(tomorrow|today|next\\s+week|\\d{4}-\\d{2}-\\d{2})");

    private final Map<String, Map<String, Object>> workflowSlots = new ConcurrentHashMap<>();

    @Override
    public WorkflowResult handle(ChatRequest request, String intent) {
        return switch (intent) {
            case "RESCHEDULE_DELIVERY" -> handleReschedule(request);
            case "TRACK_JOB" -> handleTrack(request);
            case "CREATE_TICKET" -> handleTicket(request);
            default -> defaultResponse(intent);
        };
    }

    private WorkflowResult handleReschedule(ChatRequest request) {
        Map<String, Object> slots = workflowSlots.computeIfAbsent(request.conversationId(), key -> new HashMap<>());
        for (ChatTurn turn : request.turns()) {
            if (turn.content() == null) {
                continue;
            }
            Matcher jobMatcher = JOB_PATTERN.matcher(turn.content());
            if (jobMatcher.find()) {
                slots.put("jobId", jobMatcher.group(1));
            }
            Matcher dateMatcher = DATE_PATTERN.matcher(turn.content());
            if (dateMatcher.find()) {
                slots.put("newWindow", dateMatcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        if (!slots.containsKey("jobId")) {
            return new WorkflowResult("RESCHEDULE_DELIVERY", "COLLECT_JOB_ID", slots, Optional.empty(), "Sure, which job would you like to reschedule?", null);
        }
        if (!slots.containsKey("newWindow")) {
            return new WorkflowResult("RESCHEDULE_DELIVERY", "COLLECT_WINDOW", slots, Optional.empty(), "What delivery window should I request?", null);
        }
        return new WorkflowResult("RESCHEDULE_DELIVERY", "READY_TO_EXECUTE", slots, Optional.of("RESCHEDULE_DELIVERY"),
                "I'll reschedule job %s to %s. Let me confirm that for you.".formatted(slots.get("jobId"), slots.get("newWindow")),
                null);
    }

    private WorkflowResult handleTrack(ChatRequest request) {
        Map<String, Object> slots = workflowSlots.computeIfAbsent(request.conversationId(), key -> new HashMap<>());
        for (ChatTurn turn : request.turns()) {
            if (turn.content() == null) {
                continue;
            }
            Matcher jobMatcher = JOB_PATTERN.matcher(turn.content());
            if (jobMatcher.find()) {
                slots.put("jobId", jobMatcher.group(1));
            }
        }
        if (!slots.containsKey("jobId")) {
            return new WorkflowResult("TRACK_JOB", "COLLECT_JOB_ID", slots, Optional.empty(), "Please provide the job number you'd like to track.", null);
        }
        return new WorkflowResult("TRACK_JOB", "READY_TO_EXECUTE", slots, Optional.of("TRACK_JOB"),
                "Checking the latest status for job %s.".formatted(slots.get("jobId")), null);
    }

    private WorkflowResult handleTicket(ChatRequest request) {
        Map<String, Object> slots = workflowSlots.computeIfAbsent(request.conversationId(), key -> new HashMap<>());
        slots.putIfAbsent("summary", latestUserMessage(request));
        return new WorkflowResult("CREATE_TICKET", "READY_TO_EXECUTE", slots, Optional.of("CREATE_TICKET"),
                "I'll log a support ticket with that information.", null);
    }

    private WorkflowResult defaultResponse(String intent) {
        return new WorkflowResult(intent, "RESPOND", Map.of(), Optional.empty(),
                "Let me look that up for you.", null);
    }

    private String latestUserMessage(ChatRequest request) {
        return request.turns().reversed().stream()
                .filter(turn -> turn.role() == com.netcourier.chatbot.model.ChatMessageRole.USER)
                .map(ChatTurn::content)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse("Support ticket from conversation %s".formatted(request.conversationId()));
    }
}
