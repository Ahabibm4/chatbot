package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class ToolAuditService {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditService.class);

    public void record(String toolName,
                       ChatRequest request,
                       Map<String, Object> sanitizedPayload,
                       ToolExecutionResult result,
                       boolean audited) {
        if (!audited) {
            log.debug("Tool {} executed for tenant {} by {} with status {}", toolName, request.tenantId(), request.userId(), result.success());
            return;
        }
        log.info("TOOL_AUDIT tenant={} user={} tool={} success={} detail={} payload={} timestamp={}"
                        , request.tenantId()
                        , request.userId()
                        , toolName
                        , result.success()
                        , result.detail()
                        , sanitizedPayload
                        , OffsetDateTime.now());
    }

    public void denied(String toolName, ChatRequest request, String reason) {
        log.warn("TOOL_DENIED tenant={} user={} tool={} reason={}", request.tenantId(), request.userId(), toolName, reason);
    }
}
