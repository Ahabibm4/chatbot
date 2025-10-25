package com.netcourier.chatbot.persistence.repository;

import com.netcourier.chatbot.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
}
