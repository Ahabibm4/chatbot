package com.netcourier.chatbot.service.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ToolSpecification(String name,
                                List<String> requiredRoles,
                                Map<String, Class<?>> inputSchema,
                                List<String> requiredFields,
                                boolean audit) {

    public ToolSpecification {
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }

    public boolean isAuthorized(Set<String> callerRoles) {
        if (requiredRoles.isEmpty()) {
            return true;
        }
        if (callerRoles == null || callerRoles.isEmpty()) {
            return false;
        }
        for (String required : requiredRoles) {
            if (callerRoles.stream().anyMatch(role -> role.equalsIgnoreCase(required))) {
                return true;
            }
        }
        return false;
    }

    public ValidationResult validate(Map<String, Object> slots) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (slots != null) {
            for (Map.Entry<String, Class<?>> entry : inputSchema.entrySet()) {
                String key = entry.getKey();
                Object raw = slots.get(key);
                if (raw == null) {
                    continue;
                }
                if (!entry.getValue().isInstance(raw)) {
                    return ValidationResult.invalid("Slot %s must be of type %s".formatted(key, entry.getValue().getSimpleName()));
                }
                sanitized.put(key, raw);
            }
        }
        for (String required : requiredFields) {
            if (!sanitized.containsKey(required) || Objects.toString(sanitized.get(required), "").isBlank()) {
                return ValidationResult.invalid("Required slot %s was missing".formatted(required));
            }
        }
        return ValidationResult.valid(Collections.unmodifiableMap(sanitized));
    }

    public record ValidationResult(boolean valid, String message, Map<String, Object> sanitized) {
        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, Map.of());
        }

        public static ValidationResult valid(Map<String, Object> sanitized) {
            return new ValidationResult(true, null, sanitized);
        }
    }
}
