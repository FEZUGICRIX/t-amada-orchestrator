package com.tamada.travel.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collection;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalServiceErrorResponse(
        boolean success,
        int statusCode,
        Object message,
        String error,
        Object timestamp,
        String path
) {

    public String messageText() {
        if (message instanceof Collection<?> messages) {
            return messages.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("; "));
        }
        return message == null ? null : String.valueOf(message);
    }
}
