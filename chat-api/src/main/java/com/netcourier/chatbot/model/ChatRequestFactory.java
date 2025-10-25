package com.netcourier.chatbot.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ChatRequestFactory {

    private ChatRequestFactory() {
    }

    public static ChatRequest fromSubmission(ChatSubmission submission) {
        ChatSubmissionContext context = submission.context();
        Locale locale = Locale.forLanguageTag(context.locale());
        Set<String> roles = context.roles() == null ? Set.of() : context.roles().stream().collect(Collectors.toSet());
        roles = Set.copyOf(Stream.concat(roles.stream(), Stream.of(context.ui())).collect(Collectors.toSet()));
        ChatContext chatContext = new ChatContext(locale, roles, context.ui());
        ChatTurn turn = new ChatTurn(ChatMessageRole.USER, submission.message());
        return new ChatRequest(
                submission.sessionId(),
                context.tenantId(),
                context.userId(),
                List.of(turn),
                chatContext
        );
    }
}
