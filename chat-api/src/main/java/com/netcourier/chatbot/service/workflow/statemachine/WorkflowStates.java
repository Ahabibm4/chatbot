package com.netcourier.chatbot.service.workflow.statemachine;

public enum WorkflowStates {
    START,
    RESCHEDULE_COLLECT_JOB_ID,
    RESCHEDULE_COLLECT_WINDOW,
    RESCHEDULE_READY,
    TRACK_COLLECT_JOB_ID,
    TRACK_READY,
    TICKET_COLLECT_SUMMARY,
    TICKET_READY
}
