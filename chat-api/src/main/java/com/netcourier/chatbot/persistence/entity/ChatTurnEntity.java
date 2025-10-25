package com.netcourier.chatbot.persistence.entity;

import com.netcourier.chatbot.model.ChatMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "chat_turns")
public class ChatTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private ChatMessageRole role;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "sequence_number", nullable = false)
    private int sequence;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ChatTurnEntity() {
    }

    public ChatTurnEntity(String conversationId,
                          String tenantId,
                          ChatMessageRole role,
                          String content,
                          int sequence,
                          OffsetDateTime createdAt) {
        this.conversationId = conversationId;
        this.tenantId = tenantId;
        this.role = role;
        this.content = content;
        this.sequence = sequence;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public ChatMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public int getSequence() {
        return sequence;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
