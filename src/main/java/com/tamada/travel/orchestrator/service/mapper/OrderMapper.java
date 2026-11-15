package com.tamada.travel.orchestrator.service.mapper;

import com.tamada.travel.orchestrator.dto.BookingStepResponse;
import com.tamada.travel.orchestrator.dto.CustomerContactResponse;
import com.tamada.travel.orchestrator.dto.HotelRoomResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.PassengerResponse;
import com.tamada.travel.orchestrator.model.BookingStep;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.CustomerContact;
import com.tamada.travel.orchestrator.model.Passenger;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Преобразователь доменных сущностей {@link CompositeOrder} в DTO ответа API.
 */
@Component
public class OrderMapper {

    public OrderResponse toResponse(CompositeOrder order) {
        if (order == null) {
            return null;
        }

        List<BookingStepResponse> steps = order.getBookingSteps() != null
                ? order.getBookingSteps().stream().map(this::toBookingStepResponse).toList()
                : List.of();

        List<PassengerResponse> passengers = order.getPassengers() != null
                ? order.getPassengers().stream().map(this::toPassengerResponse).toList()
                : List.of();

        CustomerContactResponse contactResponse = toCustomerContactResponse(order.getCustomerContact());

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
                contactResponse
        );
    }

    public BookingStepResponse toBookingStepResponse(BookingStep step) {
        if (step == null) {
            return null;
        }

        Integer roomsCount = null;
        List<HotelRoomResponse> roomResponses = null;

        if (step.getType() == BookingType.HOTEL && step.getRooms() != null && !step.getRooms().isEmpty()) {
            roomsCount = step.getRooms().size();
            roomResponses = step.getRooms().stream()
                    .map(r -> new HotelRoomResponse(
                            r.getId(),
                            r.getCategory(),
                            r.getTariff(),
                            r.getPrice(),
                            r.getStayConditions()
                    ))
                    .toList();
        }

        return new BookingStepResponse(
                step.getId(),
                step.getType(),
                step.getResourceId(),
                step.getStatus(),
                step.getExternalId(),
                step.getPrice(),
                roomsCount,
                roomResponses
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
}
