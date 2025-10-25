package com.netcourier.chatbot.service.intent;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DefaultIntentRouter implements IntentRouter {

    private final Map<Pattern, String> regexIntents;
    private final String fallbackIntent;

    public DefaultIntentRouter(@Value("${chat.intent.fallback:RAG_FAQ}") String fallbackIntent) {
        this.fallbackIntent = fallbackIntent;
        this.regexIntents = Map.of(
                Pattern.compile("(?i)reschedule|rebook"), "RESCHEDULE_DELIVERY",
                Pattern.compile("(?i)track|status|NC\\d{6,}"), "TRACK_JOB",
                Pattern.compile("(?i)ticket|issue"), "CREATE_TICKET"
        );
    }

    @Override
    public String route(ChatRequest request) {
        List<ChatTurn> turns = request.turns();
        if (turns.isEmpty()) {
            return fallbackIntent;
        }
        String latest = turns.get(turns.size() - 1).content();
        if (latest == null) {
            return fallbackIntent;
        }
        return regexIntents.entrySet().stream()
                .filter(entry -> entry.getKey().matcher(latest).find())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(fallbackIntent);
    }
}
