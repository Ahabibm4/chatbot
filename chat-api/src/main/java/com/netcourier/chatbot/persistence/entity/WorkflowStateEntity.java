package com.netcourier.chatbot.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "workflow_states")
public class WorkflowStateEntity {

    @EmbeddedId
    private WorkflowStateKey id;

    @Column(name = "state", nullable = false, length = 64)
    private String state;

    @Column(name = "slots_json", columnDefinition = "text")
    private String slotsJson;

    @Column(name = "last_response", columnDefinition = "text")
    private String lastResponse;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WorkflowStateEntity() {
    }

    public WorkflowStateEntity(WorkflowStateKey id, String state, String slotsJson) {
        this.id = id;
        this.state = state;
        this.slotsJson = slotsJson;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public WorkflowStateKey getId() {
        return id;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getSlotsJson() {
        return slotsJson;
    }

    public void setSlotsJson(String slotsJson) {
        this.slotsJson = slotsJson;
    }

    public String getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(String lastResponse) {
        this.lastResponse = lastResponse;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

}
