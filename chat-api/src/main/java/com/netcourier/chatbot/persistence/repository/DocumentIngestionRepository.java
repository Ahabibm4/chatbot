package com.netcourier.chatbot.persistence.repository;

import com.netcourier.chatbot.persistence.entity.DocumentIngestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentIngestionRepository extends JpaRepository<DocumentIngestionEntity, Long> {

    Optional<DocumentIngestionEntity> findTopByTenantIdAndExternalIdOrderByVersionDesc(String tenantId, String externalId);

    Optional<DocumentIngestionEntity> findTopByTenantIdAndContentHashOrderByVersionDesc(String tenantId, String contentHash);

    List<DocumentIngestionEntity> findByTenantIdAndDocumentId(String tenantId, String documentId);
}
