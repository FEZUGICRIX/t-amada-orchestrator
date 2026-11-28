package com.tamada.travel.orchestrator.service.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.dto.BookingStepResponse;
import com.tamada.travel.orchestrator.dto.CustomerContactResponse;
import com.tamada.travel.orchestrator.dto.HotelRoomResponse;
import com.tamada.travel.orchestrator.dto.OrderOperationHistoryResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.PassengerResponse;
import com.tamada.travel.orchestrator.dto.error.StepErrorDetailsResponse;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.CustomerContact;
import com.tamada.travel.orchestrator.model.OrderOperationHistory;
import com.tamada.travel.orchestrator.model.Passenger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Преобразователь доменных сущностей {@link CompositeOrder} в DTO ответа API.
 */
@Component
public class OrderMapper {

    private final ObjectMapper objectMapper;

    @Autowired
    public OrderMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public OrderMapper() {
        this(new ObjectMapper());
    }

    public OrderResponse toResponse(CompositeOrder order) {
        if (order == null) {
            return null;
        }

        List<BookingStepResponse> steps;
        try {
            steps = order.getBookingSteps() != null
                    ? order.getBookingSteps().stream().map(this::toBookingStepResponse).toList()
                    : List.of();
        } catch (Exception e) {
            steps = List.of();
        }

        List<PassengerResponse> passengers;
        try {
            passengers = order.getPassengers() != null
                    ? order.getPassengers().stream().map(this::toPassengerResponse).toList()
                    : List.of();
        } catch (Exception e) {
            passengers = List.of();
        }

        CustomerContactResponse contactResponse;
        try {
            contactResponse = toCustomerContactResponse(order.getCustomerContact());
        } catch (Exception e) {
            contactResponse = null;
        }

        List<OrderOperationHistoryResponse> historyResponses;
        try {
            historyResponses = order.getOperationHistory() != null
                    ? order.getOperationHistory().stream().map(this::toOperationHistoryResponse).toList()
                    : List.of();
        } catch (Exception e) {
            historyResponses = List.of();
        }

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getBasePrice(),
                order.getServiceFee(),
                order.getTotalPrice(),
                steps,
                passengers,
                contactResponse,
                order.getFailureReason(),
                order.getUserMessage(),
                historyResponses
        );
    }

    public BookingStepResponse toBookingStepResponse(BookingStep step) {
        if (step == null) {
            return null;
        }

        Integer roomsCount = null;
        List<HotelRoomResponse> roomResponses = null;

        if (step.getType() == BookingType.HOTEL) {
            try {
                if (step.getRooms() != null && !step.getRooms().isEmpty()) {
                    roomsCount = step.getRooms().size();
                    roomResponses = step.getRooms().stream()
                            .map(r -> new HotelRoomResponse(
                                    r.getId(),
                                    r.getVariantId(),
                                    r.getCategory(),
                                    r.getTariff(),
                                    r.getMealType(),
                                    r.getPrice(),
                                    r.getCancellationPolicy(),
                                    r.getStayConditions()
                            ))
                            .toList();
                }
            } catch (Exception e) {
                // Ignore uninitialized lazy rooms when detached
            }
        }

        StepErrorDetailsResponse errorDetails = parseErrorDetails(step.getErrorDetails());

        return new BookingStepResponse(
                step.getId(),
                step.getType(),
                step.getResourceId(),
                step.getStatus(),
                step.getExternalId(),
                step.getPrice(),
                roomsCount,
                roomResponses,
                step.getErrorCode(),
                step.getErrorMessage(),
                step.getUserMessage(),
                step.getCancellationReason(),
                errorDetails
        );
    }

    public OrderOperationHistoryResponse toOperationHistoryResponse(OrderOperationHistory history) {
        if (history == null) {
            return null;
        }

        StepErrorDetailsResponse errorDetails = parseErrorDetails(history.getDetails());

        return new OrderOperationHistoryResponse(
                history.getId(),
                history.getBookingStep() != null ? history.getBookingStep().getId() : null,
                history.getBookingType(),
                history.getOperationType(),
                history.getStatus(),
                history.getHttpStatus(),
                history.getErrorCode(),
                history.getMessage(),
                history.getUserMessage(),
                history.getCancellationReason(),
                errorDetails,
                history.getCreatedAt()
        );
    }

    public PassengerResponse toPassengerResponse(Passenger p) {
        if (p == null) {
            return null;
        }
        return new PassengerResponse(
                p.getId(),
                p.getType(),
                p.getFirstName(),
                p.getLastName(),
                p.getBirthDate(),
                p.getCitizenship(),
                p.getDocumentType(),
                p.getDocumentNumber()
        );
    }

    public CustomerContactResponse toCustomerContactResponse(CustomerContact contact) {
        if (contact == null) {
            return null;
        }
        return new CustomerContactResponse(
                contact.getId(),
                contact.getPhone(),
                contact.getEmail()
        );
    }

    private StepErrorDetailsResponse parseErrorDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StepErrorDetailsResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
