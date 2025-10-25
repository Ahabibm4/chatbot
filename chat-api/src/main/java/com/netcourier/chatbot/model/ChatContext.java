package com.netcourier.chatbot.model;

import java.util.Locale;
import java.util.Set;

public record ChatContext(
        Locale locale,
        Set<String> roles,
        String ui
) {
}
