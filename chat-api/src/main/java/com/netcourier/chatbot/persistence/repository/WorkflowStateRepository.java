package com.netcourier.chatbot.persistence.repository;

import com.netcourier.chatbot.persistence.entity.WorkflowStateEntity;
import com.netcourier.chatbot.persistence.entity.WorkflowStateKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStateRepository extends JpaRepository<WorkflowStateEntity, WorkflowStateKey> {

    List<WorkflowStateEntity> findByIdConversationId(String conversationId);
}
