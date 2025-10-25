package com.netcourier.chatbot.service.tools;

import com.netcourier.chatbot.model.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
public class TrackJobToolAdapter extends BaseApiToolAdapter {

    private final String path;

    public TrackJobToolAdapter(WebClient netCourierApiClient,
                               @Value("${chat.netcourier.track-path:/jobs/track}") String path) {
        super(netCourierApiClient);
        this.path = path;
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
