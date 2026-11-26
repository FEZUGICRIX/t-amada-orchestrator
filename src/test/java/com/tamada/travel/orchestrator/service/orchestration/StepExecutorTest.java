package com.tamada.travel.orchestrator.service.orchestration;

import com.tamada.travel.orchestrator.client.BookingClientRegistry;
import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.repository.CompositeOrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepExecutorTest {

    @Test
    void shouldForwardSelectedHotelRoomVariantDetailsInBookingRequest() {
        BookingClientRegistry clientRegistry = mock(BookingClientRegistry.class);
        StepResponseValidator responseValidator = new StepResponseValidator();
        CompositeOrderRepository repository = mock(CompositeOrderRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        UUID orderId = UUID.randomUUID();
        UUID hotelResourceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID externalBookingId = UUID.randomUUID();

        CompositeOrder order = CompositeOrder.create();
        ReflectionTestUtils.setField(order, "id", orderId);
        var hotelStep = order.addBookingStep(BookingType.HOTEL, hotelResourceId, new BigDecimal("14500.00"));
        ReflectionTestUtils.setField(hotelStep, "id", stepId);
        hotelStep.addRoom(
                "var-deluxe-hb-flex",
                "Deluxe",
                "Полупансион гибкий",
                "HB",
                new BigDecimal("14500.00"),
                "Бесплатная отмена за 48 часов до заезда",
                "Заезд с 14:00, выезд до 12:00"
        );
        hotelStep.startProcessing();

        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(clientRegistry.book(eq(BookingType.HOTEL), any(BookingRequest.class)))
                .thenReturn(new BookingResponse(externalBookingId, stepId, "CONFIRMED", Instant.now()));

        StepExecutor executor = new StepExecutor(clientRegistry, responseValidator, repository, transactionTemplate);
        StepExecutionResult result = executor.executeStep(orderId, stepId, BookingType.HOTEL, hotelResourceId);

        assertInstanceOf(StepExecutionResult.Success.class, result);
        assertEquals(BookingStatus.CONFIRMED, hotelStep.getStatus());

        ArgumentCaptor<BookingRequest> requestCaptor = ArgumentCaptor.forClass(BookingRequest.class);
        verify(clientRegistry).book(eq(BookingType.HOTEL), requestCaptor.capture());

        BookingRequest sentRequest = requestCaptor.getValue();
        assertEquals(hotelResourceId, sentRequest.resourceId());
        assertEquals(stepId, sentRequest.bookingReference());
        assertEquals(new BigDecimal("14500.00"), sentRequest.price());
        assertNotNull(sentRequest.rooms());
        assertEquals(1, sentRequest.rooms().size());

        BookingRequest.RoomDetail room = sentRequest.rooms().get(0);
        assertEquals("var-deluxe-hb-flex", room.variantId());
        assertEquals("Deluxe", room.category());
        assertEquals("Полупансион гибкий", room.tariff());
        assertEquals("HB", room.mealType());
        assertEquals(new BigDecimal("14500.00"), room.price());
        assertEquals("Бесплатная отмена за 48 часов до заезда", room.cancellationPolicy());
        assertEquals("Заезд с 14:00, выезд до 12:00", room.stayConditions());
    }
}
