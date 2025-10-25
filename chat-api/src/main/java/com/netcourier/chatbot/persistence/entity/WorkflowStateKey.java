package com.netcourier.chatbot.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class WorkflowStateKey implements Serializable {

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    public WorkflowStateKey() {
    }

    public WorkflowStateKey(String conversationId, String workflowId) {
        this.conversationId = conversationId;
        this.workflowId = workflowId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowStateKey that = (WorkflowStateKey) o;
        return Objects.equals(conversationId, that.conversationId) && Objects.equals(workflowId, that.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, workflowId);
    }
}
