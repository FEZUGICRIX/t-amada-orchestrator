package com.tamada.travel.orchestrator.service.validation;

import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.dto.PassengerRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.PassengerType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Валидатор входных параметров составного заказа и его шагов бронирования.
 */
@Component
public class OrderValidator {

    public void validateBookings(List<CreateBookingStepRequest> bookings) {
        if (bookings == null || bookings.isEmpty()) {
            throw new InvalidBookingDataException("Bookings must not be empty");
        }

        long distinctResources = bookings.stream()
                .map(CreateBookingStepRequest::resourceId)
                .distinct()
                .count();
        if (distinctResources != bookings.size()) {
            throw new InvalidBookingDataException("Booking resource IDs must not contain duplicates");
        }

        long flightCount = bookings.stream().filter(b -> b.type() == BookingType.FLIGHT).count();
        long hotelCount = bookings.stream().filter(b -> b.type() == BookingType.HOTEL).count();
        long insuranceCount = bookings.stream().filter(b -> b.type() == BookingType.INSURANCE).count();
        long transferCount = bookings.stream().filter(b -> b.type() == BookingType.TRANSFER).count();

        if (flightCount == 0) {
            throw new InvalidBookingDataException("Flight booking is mandatory for composite order");
        }
        if (flightCount > 2) {
            throw new InvalidBookingDataException("Booking types must not contain duplicates");
        }
        if (hotelCount > 1 || insuranceCount > 1 || transferCount > 1) {
            throw new InvalidBookingDataException("Booking types must not contain duplicates");
        }

        for (var booking : bookings) {
            validateBookingStep(booking);
        }
    }

    /**
     * Проверяет допустимость состава группы пассажиров.
     * Если список пассажиров не передан или пуст, валидация пропускается (обратная совместимость).
     * При наличии пассажиров требуется минимум один взрослый (ADULT), а количество младенцев (INFANT)
     * не может превышать количество взрослых.
     */
    public void validatePassengers(List<PassengerRequest> passengers) {
        if (passengers == null || passengers.isEmpty()) {
            return;
        }

        long adultsCount = 0;
        long infantsCount = 0;

        for (var passenger : passengers) {
            if (passenger == null) {
                throw new InvalidBookingDataException("Passenger must not be null");
            }
            if (passenger.type() == null) {
                throw new InvalidBookingDataException("Passenger type must not be null");
            }
            if (passenger.type() == PassengerType.ADULT) {
                adultsCount++;
            } else if (passenger.type() == PassengerType.INFANT) {
                infantsCount++;
            }
        }

        if (adultsCount == 0) {
            throw new InvalidBookingDataException("At least one adult passenger is required");
        }
        if (infantsCount > adultsCount) {
            throw new InvalidBookingDataException(
                    "Number of infants (" + infantsCount + ") cannot exceed number of adults (" + adultsCount + ")"
            );
        }
    }

    public void validateBookingStep(CreateBookingStepRequest booking) {
        if (booking == null) {
            throw new InvalidBookingDataException("Booking step must not be null");
        }
        if (booking.resourceId() == null) {
            throw new InvalidBookingDataException("Resource id must not be null");
        }
        if (booking.price() != null && booking.price().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidBookingDataException("Step price must not be negative");
        }
        if (booking.type() != BookingType.HOTEL) {
            if (booking.rooms() != null && !booking.rooms().isEmpty()) {
                throw new InvalidBookingDataException("Rooms can only be specified for HOTEL bookings");
            }
            if (booking.roomsCount() != null) {
                throw new InvalidBookingDataException("Rooms count can only be specified for HOTEL bookings");
            }
        } else {
            validateHotelBooking(booking);
        }
    }

    public void validateHotelReplacement(UUID resourceId, BigDecimal price, Integer roomsCount, List<HotelRoomRequest> rooms) {
        if (resourceId == null) {
            throw new InvalidBookingDataException("Resource id must not be null");
        }
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidBookingDataException("Step price must not be negative");
        }
        CreateBookingStepRequest dummyHotel = new CreateBookingStepRequest(BookingType.HOTEL, resourceId, price, roomsCount, rooms);
        validateHotelBooking(dummyHotel);
    }

    private void validateHotelBooking(CreateBookingStepRequest booking) {
        if (booking.rooms() != null && !booking.rooms().isEmpty()) {
            if (booking.roomsCount() != null && !booking.roomsCount().equals(booking.rooms().size())) {
                throw new InvalidBookingDataException(
                        "Rooms count (" + booking.roomsCount() + ") does not match provided rooms size (" + booking.rooms().size() + ")"
                );
            }
            BigDecimal roomsTotal = booking.rooms().stream()
                    .map(HotelRoomRequest::price)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (booking.price() != null && booking.price().compareTo(BigDecimal.ZERO) > 0) {
                if (booking.price().compareTo(roomsTotal) != 0) {
                    throw new InvalidBookingDataException(
                            "Step price (" + booking.price() + ") does not match total room price (" + roomsTotal + ")"
                    );
                }
            }
        } else if (booking.roomsCount() != null && booking.roomsCount() > 0) {
            throw new InvalidBookingDataException("Rooms parameters must be provided when roomsCount is specified");
        }
    }
}
