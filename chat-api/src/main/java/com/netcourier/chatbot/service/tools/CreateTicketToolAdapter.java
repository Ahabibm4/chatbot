package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
public class CreateTicketToolAdapter extends BaseApiToolAdapter {

    private final String path;

    public CreateTicketToolAdapter(WebClient netCourierApiClient,
                                   @Value("${chat.netcourier.ticket-path:/tickets}") String path) {
        super(netCourierApiClient);
        this.path = path;
    }

    @Override
    public String name() {
        return "CREATE_TICKET";
    }

    @Override
    public boolean supports(String toolName) {
        return name().equalsIgnoreCase(toolName);
    }

    @Override
    public ToolExecutionResult execute(ChatRequest request, Map<String, Object> slots) {
        Map<String, Object> payload = Map.of(
                "tenantId", request.tenantId(),
                "summary", slots.getOrDefault("summary", "Support ticket"),
                "reportedBy", request.userId()
        );
        try {
            apiClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            return new ToolExecutionResult(name(), true, "Ticket submitted");
        } catch (Exception e) {
            return new ToolExecutionResult(name(), false, e.getMessage());
        }
    }
}
