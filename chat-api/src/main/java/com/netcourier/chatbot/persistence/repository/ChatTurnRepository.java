package com.netcourier.chatbot.persistence.repository;

import com.netcourier.chatbot.persistence.entity.ChatTurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatTurnRepository extends JpaRepository<ChatTurnEntity, Long> {

    @Query("select coalesce(max(t.sequence), -1) from ChatTurnEntity t where t.conversationId = :conversationId")
    int findMaxSequence(@Param("conversationId") String conversationId);

    List<ChatTurnEntity> findByConversationIdOrderBySequenceAsc(String conversationId);
}
