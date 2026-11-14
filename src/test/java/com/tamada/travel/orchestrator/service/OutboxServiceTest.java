package com.tamada.travel.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tamada.travel.orchestrator.dto.outbox.OrderCreatedOutboxPayload;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OutboxMessage;
import com.tamada.travel.orchestrator.model.OutboxStatus;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    private ObjectMapper objectMapper;
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(outboxRepository, objectMapper);
    }

    @Test
    void shouldSaveOrderCreatedEventWithFullGroupAndOptions() throws Exception {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);

        UUID flightOutId = UUID.randomUUID();
        UUID flightReturnId = UUID.randomUUID();
        UUID hotelId = UUID.randomUUID();
        UUID insuranceId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        order.addBookingStep(BookingType.FLIGHT, flightOutId, new BigDecimal("10000.00"));
        order.addBookingStep(BookingType.FLIGHT, flightReturnId, new BigDecimal("10000.00"));
        order.addBookingStep(BookingType.HOTEL, hotelId, new BigDecimal("5000.00"));
        order.addBookingStep(BookingType.INSURANCE, insuranceId, new BigDecimal("1000.00"));
        order.addBookingStep(BookingType.TRANSFER, transferId, new BigDecimal("2000.00"));

        order.addPassenger(
                PassengerType.ADULT,
                "Иван",
                "Иванов",
                LocalDate.of(1985, 3, 10),
                "Россия",
                "Паспорт РФ",
                "4511 123456"
        );
        order.addPassenger(
                PassengerType.CHILD,
                "Ольга",
                "Иванова",
                LocalDate.of(2016, 7, 22),
                "Россия",
                "Свидетельство о рождении",
                "I-АА 987654"
        );
        order.addPassenger(
                PassengerType.INFANT,
                "Петр",
                "Иванов",
                LocalDate.of(2024, 2, 1),
                "Россия",
                "Свидетельство о рождении",
                "II-ББ 555444"
        );

        order.setCustomerContact("+79998887766", "ivanov@example.com");

        when(outboxRepository.save(any(OutboxMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OutboxMessage result = outboxService.saveOrderCreatedEvent(order);

        assertNotNull(result);
        assertEquals("COMPOSITE_ORDER", result.getAggregateType());
        assertEquals(orderId.toString(), result.getAggregateId());
        assertEquals("ORDER_CREATED", result.getEventType());
        assertEquals(OutboxStatus.PENDING, result.getStatus());

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());

        OutboxMessage saved = captor.getValue();
        OrderCreatedOutboxPayload payload = objectMapper.readValue(
                saved.getPayload(),
                OrderCreatedOutboxPayload.class
        );

        assertEquals(orderId, payload.orderId());
        assertEquals(new BigDecimal("28000.00"), payload.basePrice());
        assertEquals(new BigDecimal("187.60"), payload.serviceFee());
        assertEquals(new BigDecimal("28187.60"), payload.totalPrice());
        assertNotNull(payload.customerContact());
        assertEquals("+79998887766", payload.customerContact().phone());
        assertEquals("ivanov@example.com", payload.customerContact().email());

        // Verify group composition
        assertEquals(3, payload.groupComposition().totalPassengers());
        assertEquals(1, payload.groupComposition().adultsCount());
        assertEquals(1, payload.groupComposition().childrenCount());
        assertEquals(1, payload.groupComposition().infantsCount());
        assertEquals(3, payload.groupComposition().passengers().size());

        // Verify options
        assertEquals(5, payload.selectedOptions().size());
        assertEquals(BookingType.FLIGHT, payload.selectedOptions().get(0).type());
        assertEquals(flightOutId, payload.selectedOptions().get(0).resourceId());
        assertEquals(new BigDecimal("10000.00"), payload.selectedOptions().get(0).price());
        assertEquals(BookingType.FLIGHT, payload.selectedOptions().get(1).type());
        assertEquals(flightReturnId, payload.selectedOptions().get(1).resourceId());
        assertEquals(BookingType.HOTEL, payload.selectedOptions().get(2).type());
        assertEquals(BookingType.INSURANCE, payload.selectedOptions().get(3).type());
        assertEquals(BookingType.TRANSFER, payload.selectedOptions().get(4).type());
    }

    @Test
    void shouldSaveOrderCreatedEventWithHotelRooms() throws Exception {
        UUID orderId = UUID.randomUUID();
        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);

        UUID hotelId = UUID.randomUUID();
        var step = order.addBookingStep(BookingType.HOTEL, hotelId, BigDecimal.ZERO);
        step.addRoom("Люкс", "С завтраком", new BigDecimal("12000.00"), "Завтрак включен");
        step.addRoom("Стандарт", "Базовый", new BigDecimal("7000.00"), "Без отмены");
        step.recalculatePriceFromRooms();
        order.recalculatePricing();

        when(outboxRepository.save(any(OutboxMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OutboxMessage result = outboxService.saveOrderCreatedEvent(order);
        assertNotNull(result);

        OrderCreatedOutboxPayload payload = objectMapper.readValue(
                result.getPayload(),
                OrderCreatedOutboxPayload.class
        );

        assertEquals(1, payload.selectedOptions().size());
        var hotelOption = payload.selectedOptions().get(0);
        assertEquals(BookingType.HOTEL, hotelOption.type());
        assertEquals(new BigDecimal("19000.00"), hotelOption.price());
        assertEquals(2, hotelOption.roomsCount());
        assertNotNull(hotelOption.rooms());
        assertEquals(2, hotelOption.rooms().size());

        assertEquals("Люкс", hotelOption.rooms().get(0).category());
        assertEquals("С завтраком", hotelOption.rooms().get(0).tariff());
        assertEquals(new BigDecimal("12000.00"), hotelOption.rooms().get(0).price());
        assertEquals("Завтрак включен", hotelOption.rooms().get(0).stayConditions());

        assertEquals("Стандарт", hotelOption.rooms().get(1).category());
        assertEquals("Базовый", hotelOption.rooms().get(1).tariff());
        assertEquals(new BigDecimal("7000.00"), hotelOption.rooms().get(1).price());
        assertEquals("Без отмены", hotelOption.rooms().get(1).stayConditions());
    }
}
