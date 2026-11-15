package com.tamada.travel.orchestrator.service.mapper;

import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.PassengerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderMapperTest {

    private OrderMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrderMapper();
    }

    @Test
    void shouldReturnNullWhenOrderIsNull() {
        assertNull(mapper.toResponse(null));
        assertNull(mapper.toBookingStepResponse(null));
        assertNull(mapper.toPassengerResponse(null));
        assertNull(mapper.toCustomerContactResponse(null));
    }

    @Test
    void shouldMapCompleteOrderStructure() {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);

        var flightStep = order.addBookingStep(BookingType.FLIGHT, UUID.randomUUID(), new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(flightStep, "id", UUID.randomUUID());

        var hotelStep = order.addBookingStep(BookingType.HOTEL, UUID.randomUUID(), new BigDecimal("15000.00"));
        ReflectionTestUtils.setField(hotelStep, "id", UUID.randomUUID());
        hotelStep.addRoom("Люкс", "С завтраком", new BigDecimal("15000.00"), "Завтрак включен");

        order.addPassenger(
                PassengerType.ADULT,
                "Иван",
                "Иванов",
                LocalDate.of(1990, 1, 1),
                "Россия",
                "Паспорт РФ",
                "1234 567890"
        );

        order.setCustomerContact("+79991112233", "ivanov@test.ru");

        OrderResponse response = mapper.toResponse(order);

        assertNotNull(response);
        assertEquals(orderId, response.id());
        assertEquals(OrderStatus.NEW, response.status());
        assertEquals(2, response.bookingSteps().size());
        assertEquals(1, response.passengers().size());
        assertNotNull(response.customerContact());
        assertEquals("+79991112233", response.customerContact().phone());

        var hotelResponse = response.bookingSteps().get(1);
        assertEquals(BookingType.HOTEL, hotelResponse.type());
        assertEquals(1, hotelResponse.roomsCount());
        assertNotNull(hotelResponse.rooms());
        assertEquals(1, hotelResponse.rooms().size());
        assertEquals("Люкс", hotelResponse.rooms().get(0).category());
    }
}
