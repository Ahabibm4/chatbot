package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class RescheduleDeliveryToolAdapter extends BaseApiToolAdapter {

    private final String path;
    private final ToolSpecification specification;

    public RescheduleDeliveryToolAdapter(WebClient netCourierApiClient,
                                         @Value("${chat.netcourier.reschedule-path:/jobs/reschedule}") String path) {
        super(netCourierApiClient);
        this.path = path;
        this.specification = new ToolSpecification(
                name(),
                List.of("BO"),
                Map.of(
                        "jobId", String.class,
                        "newWindow", String.class
                ),
                List.of("jobId", "newWindow"),
                true
        );
    }

    @Override
    public String name() {
        return "RESCHEDULE_DELIVERY";
    }

    @Override
    public boolean supports(String toolName) {
        return name().equalsIgnoreCase(toolName);
    }

    @Override
    public ToolSpecification specification() {
        return specification;
    }

    @Override
    public ToolExecutionResult execute(ChatRequest request, Map<String, Object> slots) {
        Map<String, Object> payload = Map.of(
                "jobId", slots.get("jobId"),
                "newWindow", slots.get("newWindow"),
                "requestedBy", request.userId()
        );
        try {
            netCourierCall(payload).block();
            return new ToolExecutionResult(name(), true, "Reschedule requested");
        } catch (Exception e) {
            return new ToolExecutionResult(name(), false, e.getMessage());
        }
    }

    private Mono<Void> netCourierCall(Map<String, Object> payload) {
        return apiClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class);
    }
}
