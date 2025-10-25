package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class TrackJobToolAdapter extends BaseApiToolAdapter {

    private final String path;
    private final ToolSpecification specification;

    public TrackJobToolAdapter(WebClient netCourierApiClient,
                               @Value("${chat.netcourier.track-path:/jobs/track}") String path) {
        super(netCourierApiClient);
        this.path = path;
        this.specification = new ToolSpecification(
                name(),
                List.of("CP", "BO"),
                Map.of("jobId", String.class),
                List.of("jobId"),
                false
        );
    }

    @Override
    public String name() {
        return "TRACK_JOB";
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
        try {
            Map<?, ?> response = apiClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("jobId", slots.get("jobId"))
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return new ToolExecutionResult(name(), true, response == null ? "No status" : response.toString());
        } catch (Exception e) {
            return new ToolExecutionResult(name(), false, e.getMessage());
        }
    }
}
