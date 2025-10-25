package com.netcourier.chatbot.service.intent;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.ChatTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class DefaultIntentRouter implements IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentRouter.class);

    private final Map<Pattern, String> regexIntents;
    private final String fallbackIntent;
    private final LlmIntentClassifier llmClassifier;
    private final boolean llmEnabled;
    private final double llmThreshold;
    private final List<String> candidateIntents;

    public DefaultIntentRouter(@Value("${chat.intent.fallback:RAG_FAQ}") String fallbackIntent,
                               LlmIntentClassifier llmClassifier,
                               @Value("${chat.intent.llm.enabled:true}") boolean llmEnabled,
                               @Value("${chat.intent.llm.threshold:0.55}") double llmThreshold) {
        this.fallbackIntent = fallbackIntent;
        this.llmClassifier = llmClassifier;
        this.llmEnabled = llmEnabled;
        this.llmThreshold = llmThreshold;
        this.regexIntents = Map.of(
                Pattern.compile("(?i)reschedule|rebook"), "RESCHEDULE_DELIVERY",
                Pattern.compile("(?i)track|status|NC\\d{6,}"), "TRACK_JOB",
                Pattern.compile("(?i)ticket|issue"), "CREATE_TICKET"
        );
        this.candidateIntents = Stream.concat(regexIntents.values().stream(), Stream.of(fallbackIntent))
                .distinct()
                .toList();
    }

    @Override
    public String route(ChatRequest request) {
        List<ChatTurn> turns = request.turns();
        if (turns.isEmpty()) {
            return fallbackIntent;
        }
        String latest = turns.getLast().content();
        if (latest == null) {
            return fallbackIntent;
        }
        Optional<String> regexIntent = regexIntents.entrySet().stream()
                .filter(entry -> entry.getKey().matcher(latest).find())
                .map(Map.Entry::getValue)
                .findFirst();
        if (regexIntent.isPresent()) {
            return regexIntent.get();
        }
        if (!llmEnabled) {
            return fallbackIntent;
        }
        Optional<LlmIntentClassifier.Classification> classification = llmClassifier.classify(request, candidateIntents);
        if (classification.isPresent()) {
            LlmIntentClassifier.Classification result = classification.get();
            if (result.confidence() >= llmThreshold) {
                log.debug("LLM classified intent {} with confidence {}", result.intent(), result.confidence());
                return result.intent();
            }
            log.debug("LLM classification {} below threshold {}", result.intent(), llmThreshold);
        }
        return fallbackIntent;
    }
}
