package com.netcourier.chatbot.service.workflow;

import com.netcourier.chatbot.model.ChatMessageRole;
import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import com.netcourier.chatbot.model.WorkflowResult;
import com.netcourier.chatbot.persistence.entity.WorkflowStateKey;
import com.netcourier.chatbot.persistence.repository.WorkflowStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StateMachineWorkflowEngineTest {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private WorkflowStateRepository workflowStateRepository;

    @Test
    void promptsForMissingJobInformation() {
        ChatRequest request = new ChatRequest(
                "conv-201",
                "tenant-1",
                "user-1",
                List.of(new ChatTurn(ChatMessageRole.USER, "Can you reschedule my delivery?")),
                null
        );

        WorkflowResult result = workflowEngine.handle(request, "RESCHEDULE_DELIVERY");

        assertThat(result.state()).isEqualTo("RESCHEDULE_COLLECT_JOB_ID");
        assertThat(result.responseMessage()).contains("which job");
        assertThat(result.toolToInvoke()).isEmpty();

        WorkflowStateKey key = new WorkflowStateKey("conv-201", "RESCHEDULE_DELIVERY");
        assertThat(workflowStateRepository.findById(key)).isPresent();
    }

    @Test
    void advancesToReadyWhenDetailsProvided() {
        ChatRequest request = new ChatRequest(
                "conv-202",
                "tenant-1",
                "user-1",
                List.of(
                        new ChatTurn(ChatMessageRole.USER, "I need to move NC654321"),
                        new ChatTurn(ChatMessageRole.USER, "Next week works")
                ),
                null
        );

        WorkflowResult result = workflowEngine.handle(request, "RESCHEDULE_DELIVERY");

        assertThat(result.state()).isEqualTo("RESCHEDULE_READY");
        assertThat(result.toolToInvoke()).contains("RESCHEDULE_DELIVERY");
        assertThat(result.slots())
                .containsEntry("jobId", "NC654321")
                .containsEntry("newWindow", "next week");
    }

    @Test
    void tracksJobWithStatePersistence() {
        ChatRequest request = new ChatRequest(
                "conv-203",
                "tenant-1",
                "user-2",
                List.of(new ChatTurn(ChatMessageRole.USER, "Track NC777777")),
                null
        );

        WorkflowResult first = workflowEngine.handle(request, "TRACK_JOB");
        assertThat(first.state()).isEqualTo("TRACK_READY");
        assertThat(first.toolToInvoke()).contains("TRACK_JOB");

        WorkflowResult again = workflowEngine.handle(request, "TRACK_JOB");
        assertThat(again.state()).isEqualTo("TRACK_READY");
        assertThat(again.slots()).containsEntry("jobId", "NC777777");
    }
}
