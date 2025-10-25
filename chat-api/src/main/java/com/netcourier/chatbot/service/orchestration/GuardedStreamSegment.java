package com.netcourier.chatbot.service.orchestration;

import com.netcourier.chatbot.model.Citation;

import java.util.List;

public record GuardedStreamSegment(Type type, String text, List<Citation> citations, String guardrailAction) {

    public enum Type {
        PARTIAL,
        FINAL
    }

    public static GuardedStreamSegment partial(String text) {
        return new GuardedStreamSegment(Type.PARTIAL, text, null, null);
    }

    public static GuardedStreamSegment finalSegment(String text, List<Citation> citations, String guardrailAction) {
        return new GuardedStreamSegment(Type.FINAL, text, citations, guardrailAction);
    }
}
