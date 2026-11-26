package com.tamada.travel.orchestrator.service.validation;

import com.tamada.travel.orchestrator.dto.CreateBookingStepRequest;
import com.tamada.travel.orchestrator.dto.HotelRoomRequest;
import com.tamada.travel.orchestrator.dto.PassengerRequest;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.PassengerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderValidatorTest {

    private OrderValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrderValidator();
    }

    @Test
    void shouldAcceptValidBookings() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.HOTEL, UUID.randomUUID())
        );

        assertDoesNotThrow(() -> validator.validateBookings(bookings));
    }

    @Test
    void shouldAcceptTwoFlightsInOneOrder() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID())
        );

        assertDoesNotThrow(() -> validator.validateBookings(bookings));
    }

    @Test
    void shouldRejectMoreThanTwoFlights() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID())
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("duplicates"));
    }

    @Test
    void shouldRejectNullOrEmptyBookings() {
        assertThrows(InvalidBookingDataException.class, () -> validator.validateBookings(null));
        assertThrows(InvalidBookingDataException.class, () -> validator.validateBookings(Collections.emptyList()));
    }

    @Test
    void shouldRejectDuplicateResourceIds() {
        UUID resourceId = UUID.randomUUID();
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, resourceId),
                new CreateBookingStepRequest(BookingType.HOTEL, resourceId)
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("resource IDs must not contain duplicates"));
    }

    @Test
    void shouldRejectOrderWithoutFlight() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.HOTEL, UUID.randomUUID())
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("Flight booking is mandatory"));
    }

    @Test
    void shouldRejectDuplicateHotelType() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.HOTEL, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.HOTEL, UUID.randomUUID())
        );

        assertThrows(InvalidBookingDataException.class, () -> validator.validateBookings(bookings));
    }

    @Test
    void shouldRejectDuplicateInsuranceType() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.INSURANCE, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.INSURANCE, UUID.randomUUID())
        );

        assertThrows(InvalidBookingDataException.class, () -> validator.validateBookings(bookings));
    }

    @Test
    void shouldRejectDuplicateTransferType() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.TRANSFER, UUID.randomUUID()),
                new CreateBookingStepRequest(BookingType.TRANSFER, UUID.randomUUID())
        );

        assertThrows(InvalidBookingDataException.class, () -> validator.validateBookings(bookings));
    }

    @Test
    void shouldRejectRoomsOnNonHotelStep() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(
                        BookingType.FLIGHT,
                        UUID.randomUUID(),
                        new BigDecimal("10000.00"),
                        1,
                        List.of(new HotelRoomRequest("Люкс", "С завтраком", new BigDecimal("10000.00")))
                )
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("Rooms can only be specified for HOTEL"));
    }

    @Test
    void shouldRejectRoomsCountMismatch() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(
                        BookingType.HOTEL,
                        UUID.randomUUID(),
                        new BigDecimal("20000.00"),
                        3,
                        List.of(
                                new HotelRoomRequest("Люкс", "Тариф", new BigDecimal("10000.00")),
                                new HotelRoomRequest("Стандарт", "Тариф", new BigDecimal("10000.00"))
                        )
                )
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("does not match provided rooms size"));
    }

    @Test
    void shouldRejectRoomsPriceMismatch() {
        List<CreateBookingStepRequest> bookings = List.of(
                new CreateBookingStepRequest(BookingType.FLIGHT, UUID.randomUUID()),
                new CreateBookingStepRequest(
                        BookingType.HOTEL,
                        UUID.randomUUID(),
                        new BigDecimal("15000.00"),
                        2,
                        List.of(
                                new HotelRoomRequest("Люкс", "Тариф", new BigDecimal("10000.00")),
                                new HotelRoomRequest("Стандарт", "Тариф", new BigDecimal("10000.00"))
                        )
                )
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validateBookings(bookings)
        );
        assertTrue(ex.getMessage().contains("does not match total room price"));
    }

    @Test
    void shouldAcceptNullOrEmptyPassengers() {
        assertDoesNotThrow(() -> validator.validatePassengers(null));
        assertDoesNotThrow(() -> validator.validatePassengers(Collections.emptyList()));
    }

    @Test
    void shouldAcceptValidPassengerGroup() {
        List<PassengerRequest> passengers = List.of(
                passenger(PassengerType.ADULT),
                passenger(PassengerType.CHILD),
                passenger(PassengerType.INFANT)
        );

        assertDoesNotThrow(() -> validator.validatePassengers(passengers));
    }

    @Test
    void shouldRejectPassengerGroupWithoutAdult() {
        List<PassengerRequest> passengers = List.of(
                passenger(PassengerType.CHILD),
                passenger(PassengerType.INFANT)
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validatePassengers(passengers)
        );
        assertTrue(ex.getMessage().contains("adult"));
    }

    @Test
    void shouldRejectPassengerGroupWhenInfantsExceedAdults() {
        List<PassengerRequest> passengers = List.of(
                passenger(PassengerType.ADULT),
                passenger(PassengerType.INFANT),
                passenger(PassengerType.INFANT)
        );

        InvalidBookingDataException ex = assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validatePassengers(passengers)
        );
        assertTrue(ex.getMessage().contains("infants"));
    }

    @Test
    void shouldRejectNullPassengerOrNullType() {
        assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validatePassengers(Arrays.asList((PassengerRequest) null))
        );
        assertThrows(
                InvalidBookingDataException.class,
                () -> validator.validatePassengers(List.of(passenger(null)))
        );
    }

    private PassengerRequest passenger(PassengerType type) {
        return new PassengerRequest(
                type,
                "Иван",
                "Иванов",
                LocalDate.of(1990, 1, 1),
                "Россия",
                "Паспорт РФ",
                "1234 567890"
        );
    }
}
