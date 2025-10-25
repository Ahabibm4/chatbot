package com.netcourier.chatbot.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.persistence.entity.WorkflowStateEntity;
import com.netcourier.chatbot.persistence.entity.WorkflowStateKey;
import com.netcourier.chatbot.persistence.repository.WorkflowStateRepository;
import com.netcourier.chatbot.service.workflow.statemachine.WorkflowEvents;
import com.netcourier.chatbot.service.workflow.statemachine.WorkflowStateMachineConfig;
import com.netcourier.chatbot.service.workflow.statemachine.WorkflowStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StateMachineWorkflowEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(StateMachineWorkflowEngine.class);
    private static final Pattern JOB_PATTERN = Pattern.compile("(NC\\d{6,})");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?i)(tomorrow|today|next\\s+week|\\d{4}-\\d{2}-\\d{2})");

    private final StateMachineFactory<WorkflowStates, WorkflowEvents> stateMachineFactory;
    private final WorkflowStateRepository workflowStateRepository;
    private final ObjectMapper objectMapper;

    public StateMachineWorkflowEngine(StateMachineFactory<WorkflowStates, WorkflowEvents> stateMachineFactory,
                                      WorkflowStateRepository workflowStateRepository,
                                      ObjectMapper objectMapper) {
        this.stateMachineFactory = stateMachineFactory;
        this.workflowStateRepository = workflowStateRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowResult handle(ChatRequest request, String intent) {
        String workflowId = workflowIdForIntent(intent);
        WorkflowStateKey key = new WorkflowStateKey(request.conversationId(), workflowId);

        WorkflowStateEntity persisted = workflowStateRepository.findById(key).orElse(null);
        WorkflowStates initialState = persisted == null ? WorkflowStates.START : WorkflowStates.valueOf(persisted.getState());
        Map<String, Object> slots = persisted == null ? new HashMap<>() : readSlots(persisted);

        StateMachine<WorkflowStates, WorkflowEvents> machine = stateMachineFactory.getStateMachine(key.toString());
        machine.stop();
        machine.getStateMachineAccessor().doWithAllRegions(access -> {
            StateMachineContext<WorkflowStates, WorkflowEvents> context =
                    new DefaultStateMachineContext<>(initialState, null, null, null);
            access.resetStateMachine(context);
        });
        machine.getExtendedState().getVariables().put(WorkflowStateMachineConfig.SLOTS_VARIABLE, new HashMap<>(slots));
        machine.start();

        applyIntent(machine, request, intent, slots);

        WorkflowStates currentState = machine.getState().getId();
        Map<String, Object> updatedSlots = getSlots(machine);
        WorkflowResult result = buildResult(workflowId, currentState, updatedSlots);

        persistState(key, currentState, updatedSlots, result);
        return result;
    }

    private void applyIntent(StateMachine<WorkflowStates, WorkflowEvents> machine,
                             ChatRequest request,
                             String intent,
                             Map<String, Object> slots) {
        switch (intent) {
            case "RESCHEDULE_DELIVERY" -> handleReschedule(machine, request, slots);
            case "TRACK_JOB" -> handleTrack(machine, request, slots);
            case "CREATE_TICKET" -> handleTicket(machine, request, slots);
            default -> machine.sendEvent(WorkflowEvents.RESET);
        }
    }

    private void handleReschedule(StateMachine<WorkflowStates, WorkflowEvents> machine,
                                  ChatRequest request,
                                  Map<String, Object> slots) {
        if (machine.getState().getId() == WorkflowStates.START) {
            machine.sendEvent(WorkflowEvents.START_RESCHEDULE);
        }
        extractJobId(request).filter(job -> !job.equals(slots.get("jobId")))
                .map(job -> MessageBuilder.withPayload(WorkflowEvents.JOB_ID_CAPTURED)
                        .setHeader(WorkflowStateMachineConfig.SLOT_VALUE_HEADER, job)
                        .build())
                .ifPresent(machine::sendEvent);
        extractWindow(request).filter(window -> !window.equals(slots.get("newWindow")))
                .map(window -> MessageBuilder.withPayload(WorkflowEvents.WINDOW_CAPTURED)
                        .setHeader(WorkflowStateMachineConfig.SLOT_VALUE_HEADER, window)
                        .build())
                .ifPresent(machine::sendEvent);
    }

    private void handleTrack(StateMachine<WorkflowStates, WorkflowEvents> machine,
                             ChatRequest request,
                             Map<String, Object> slots) {
        if (machine.getState().getId() == WorkflowStates.START) {
            machine.sendEvent(WorkflowEvents.START_TRACK);
        }
        extractJobId(request).filter(job -> !job.equals(slots.get("jobId")))
                .map(job -> MessageBuilder.withPayload(WorkflowEvents.JOB_ID_CAPTURED)
                        .setHeader(WorkflowStateMachineConfig.SLOT_VALUE_HEADER, job)
                        .build())
                .ifPresent(machine::sendEvent);
    }

    private void handleTicket(StateMachine<WorkflowStates, WorkflowEvents> machine,
                               ChatRequest request,
                               Map<String, Object> slots) {
        if (machine.getState().getId() == WorkflowStates.START) {
            machine.sendEvent(WorkflowEvents.START_TICKET);
        }
        latestUserMessage(request)
                .filter(summary -> !summary.equals(slots.get("summary")))
                .map(summary -> MessageBuilder.withPayload(WorkflowEvents.SUMMARY_CAPTURED)
                        .setHeader(WorkflowStateMachineConfig.SLOT_VALUE_HEADER, summary)
                        .build())
                .ifPresent(machine::sendEvent);
    }

    private Map<String, Object> getSlots(StateMachine<WorkflowStates, WorkflowEvents> machine) {
        Map<String, Object> variables = machine.getExtendedState().getVariables();
        Object value = variables.get(WorkflowStateMachineConfig.SLOTS_VARIABLE);
        if (value instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }

    private WorkflowResult buildResult(String workflowId,
                                       WorkflowStates state,
                                       Map<String, Object> slots) {
        Optional<String> tool = Optional.empty();
        String response;
        switch (state) {
            case RESCHEDULE_COLLECT_JOB_ID -> response = "Sure, which job would you like to reschedule?";
            case RESCHEDULE_COLLECT_WINDOW -> response = "What delivery window should I request?";
            case RESCHEDULE_READY -> {
                tool = Optional.of("RESCHEDULE_DELIVERY");
                response = "I'll reschedule job %s to %s. Let me confirm that for you.".formatted(
                        slots.getOrDefault("jobId", ""),
                        slots.getOrDefault("newWindow", "the requested window"));
            }
            case TRACK_COLLECT_JOB_ID -> response = "Please provide the job number you'd like to track.";
            case TRACK_READY -> {
                tool = Optional.of("TRACK_JOB");
                response = "Checking the latest status for job %s.".formatted(slots.getOrDefault("jobId", ""));
            }
            case TICKET_COLLECT_SUMMARY -> response = "Could you share a quick summary for the ticket?";
            case TICKET_READY -> {
                tool = Optional.of("CREATE_TICKET");
                response = "I'll log a support ticket with that information.";
            }
            default -> response = "Let me look that up for you.";
        }
        return new WorkflowResult(workflowId, state.name(), slots, tool, response, null);
    }

    private void persistState(WorkflowStateKey key,
                              WorkflowStates state,
                              Map<String, Object> slots,
                              WorkflowResult result) {
        try {
            WorkflowStateEntity entity = workflowStateRepository.findById(key)
                    .orElseGet(() -> new WorkflowStateEntity(key, state.name(), objectMapper.writeValueAsString(slots)));
            entity.setState(state.name());
            entity.setSlotsJson(objectMapper.writeValueAsString(slots));
            entity.setLastResponse(result.responseMessage());
            entity.setToolName(result.toolToInvoke().orElse(null));
            workflowStateRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist workflow state {}", key.getWorkflowId(), e);
        }
    }

    private Map<String, Object> readSlots(WorkflowStateEntity entity) {
        try {
            return objectMapper.readValue(Optional.ofNullable(entity.getSlotsJson()).orElse("{}"),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize workflow slots", e);
            return new HashMap<>();
        }
    }

    private Optional<String> extractJobId(ChatRequest request) {
        for (ChatTurn turn : request.turns()) {
            if (turn.content() == null) {
                continue;
            }
            Matcher matcher = JOB_PATTERN.matcher(turn.content());
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractWindow(ChatRequest request) {
        for (ChatTurn turn : request.turns()) {
            if (turn.content() == null) {
                continue;
            }
            Matcher matcher = DATE_PATTERN.matcher(turn.content());
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private Optional<String> latestUserMessage(ChatRequest request) {
        return request.turns().reversed().stream()
                .filter(turn -> turn.role() == ChatMessageRole.USER)
                .map(ChatTurn::content)
                .filter(content -> content != null && !content.isBlank())
                .findFirst();
    }

    private String workflowIdForIntent(String intent) {
        return switch (intent) {
            case "RESCHEDULE_DELIVERY" -> "RESCHEDULE_DELIVERY";
            case "TRACK_JOB" -> "TRACK_JOB";
            case "CREATE_TICKET" -> "CREATE_TICKET";
            default -> intent;
        };
    }
}
