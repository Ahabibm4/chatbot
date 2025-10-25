package com.netcourier.chatbot.service.workflow.statemachine;

import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableStateMachineFactory
public class WorkflowStateMachineConfig extends EnumStateMachineConfigurerAdapter<WorkflowStates, WorkflowEvents> {

    public static final String SLOT_VALUE_HEADER = "slotValue";
    public static final String SLOTS_VARIABLE = "slots";

    @Override
    public void configure(StateMachineStateConfigurer<WorkflowStates, WorkflowEvents> states) throws Exception {
        states.withStates()
                .initial(WorkflowStates.START)
                .states(EnumSet.allOf(WorkflowStates.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<WorkflowStates, WorkflowEvents> transitions) throws Exception {
        transitions
                .withExternal()
                    .source(WorkflowStates.START)
                    .target(WorkflowStates.RESCHEDULE_COLLECT_JOB_ID)
                    .event(WorkflowEvents.START_RESCHEDULE)
                .and()
                .withExternal()
                    .source(WorkflowStates.RESCHEDULE_COLLECT_JOB_ID)
                    .target(WorkflowStates.RESCHEDULE_COLLECT_WINDOW)
                    .event(WorkflowEvents.JOB_ID_CAPTURED)
                    .guard(hasSlotValue())
                    .action(storeSlot("jobId"))
                .and()
                .withExternal()
                    .source(WorkflowStates.RESCHEDULE_COLLECT_WINDOW)
                    .target(WorkflowStates.RESCHEDULE_READY)
                    .event(WorkflowEvents.WINDOW_CAPTURED)
                    .guard(hasSlotValue())
                    .action(storeSlot("newWindow"))
                .and()
                .withExternal()
                    .source(WorkflowStates.START)
                    .target(WorkflowStates.TRACK_COLLECT_JOB_ID)
                    .event(WorkflowEvents.START_TRACK)
                .and()
                .withExternal()
                    .source(WorkflowStates.TRACK_COLLECT_JOB_ID)
                    .target(WorkflowStates.TRACK_READY)
                    .event(WorkflowEvents.JOB_ID_CAPTURED)
                    .guard(hasSlotValue())
                    .action(storeSlot("jobId"))
                .and()
                .withExternal()
                    .source(WorkflowStates.START)
                    .target(WorkflowStates.TICKET_COLLECT_SUMMARY)
                    .event(WorkflowEvents.START_TICKET)
                .and()
                .withExternal()
                    .source(WorkflowStates.TICKET_COLLECT_SUMMARY)
                    .target(WorkflowStates.TICKET_READY)
                    .event(WorkflowEvents.SUMMARY_CAPTURED)
                    .guard(hasSlotValue())
                    .action(storeSlot("summary"))
                .and()
                .withExternal()
                    .source(WorkflowStates.RESCHEDULE_READY)
                    .target(WorkflowStates.RESCHEDULE_COLLECT_JOB_ID)
                    .event(WorkflowEvents.RESET)
                    .action(clearSlots())
                .and()
                .withExternal()
                    .source(WorkflowStates.TRACK_READY)
                    .target(WorkflowStates.TRACK_COLLECT_JOB_ID)
                    .event(WorkflowEvents.RESET)
                    .action(clearSlots())
                .and()
                .withExternal()
                    .source(WorkflowStates.TICKET_READY)
                    .target(WorkflowStates.TICKET_COLLECT_SUMMARY)
                    .event(WorkflowEvents.RESET)
                    .action(clearSlots());
    }

    private Guard<WorkflowStates, WorkflowEvents> hasSlotValue() {
        return context -> {
            Object value = context.getMessageHeaders().get(SLOT_VALUE_HEADER);
            if (value instanceof String str) {
                return !str.isBlank();
            }
            return value != null;
        };
    }

    private Action<WorkflowStates, WorkflowEvents> storeSlot(String slotKey) {
        return context -> {
            Map<String, Object> slots = ensureSlotsMap(context.getExtendedState().getVariables());
            Object value = context.getMessageHeaders().get(SLOT_VALUE_HEADER);
            if (value != null) {
                slots.put(slotKey, value);
            }
        };
    }

    private Action<WorkflowStates, WorkflowEvents> clearSlots() {
        return context -> ensureSlotsMap(context.getExtendedState().getVariables()).clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureSlotsMap(Map<String, Object> variables) {
        return (Map<String, Object>) variables.computeIfAbsent(SLOTS_VARIABLE, key -> new ConcurrentHashMap<>());
    }
}
