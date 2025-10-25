package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.Citation;

import java.util.List;

public record GuardedResponse(String answer,
                              List<Citation> citations,
                              String guardrailAction) {
}
