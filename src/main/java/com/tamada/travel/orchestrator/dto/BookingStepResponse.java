package com.tamada.travel.orchestrator.dto;

import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;

import java.util.UUID;

public record BookingStepResponse(
        UUID id,
        BookingType type,
        BookingStatus status,
        String externalId
) {
}