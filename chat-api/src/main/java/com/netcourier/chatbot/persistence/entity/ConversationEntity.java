package com.netcourier.chatbot.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @Column(name = "conversation_id", nullable = false, updatable = false, length = 64)
    private String conversationId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ConversationEntity() {
    }

    public ConversationEntity(String conversationId, String tenantId) {
        this.conversationId = conversationId;
        this.tenantId = tenantId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
