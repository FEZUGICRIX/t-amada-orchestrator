package com.tamada.travel.orchestrator.service.error;

import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.model.BookingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserErrorMessageResolverTest {

    private UserErrorMessageResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UserErrorMessageResolver();
    }

    @Test
    void shouldResolveResourceUnavailableMessagesForDifferentServices() {
        String hotelMsg = resolver.resolveBookingErrorMessage(
                BookingType.HOTEL,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                409,
                "Resource is unavailable"
        );
        assertTrue(hotelMsg.contains("номер в отеле недоступен"));

        String flightMsg = resolver.resolveBookingErrorMessage(
                BookingType.FLIGHT,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                409,
                "Flight is full"
        );
        assertTrue(flightMsg.contains("авиарейс недоступен"));

        String insuranceMsg = resolver.resolveBookingErrorMessage(
                BookingType.INSURANCE,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                409,
                "Policy unavailable"
        );
        assertTrue(insuranceMsg.contains("страхового полиса временно недоступно"));

        String transferMsg = resolver.resolveBookingErrorMessage(
                BookingType.TRANSFER,
                ExternalBookingErrorType.RESOURCE_UNAVAILABLE,
                409,
                "No cars"
        );
        assertTrue(transferMsg.contains("трансфер или автомобиль недоступен"));
    }

    @Test
    void shouldResolveNotFoundMessages() {
        String msg = resolver.resolveBookingErrorMessage(
                BookingType.HOTEL,
                ExternalBookingErrorType.NOT_FOUND,
                404,
                "Not found"
        );
        assertTrue(msg.contains("не найдена в каталоге поставщика"));
        assertTrue(msg.contains("отеля"));
    }

    @Test
    void shouldResolveValidationErrorMessagesWithSupplierDetails() {
        String msg = resolver.resolveBookingErrorMessage(
                BookingType.FLIGHT,
                ExternalBookingErrorType.VALIDATION_ERROR,
                400,
                "passenger birth date is required"
        );
        assertTrue(msg.contains("Некорректные параметры бронирования"));
        assertTrue(msg.contains("passenger birth date is required"));
    }

    @Test
    void shouldResolveSystemFailureMessages() {
        String msg = resolver.resolveBookingErrorMessage(
                BookingType.TRANSFER,
                ExternalBookingErrorType.SYSTEM_FAILURE,
                503,
                "Service Unavailable"
        );
        assertTrue(msg.contains("временно недоступен или не отвечает"));
        assertTrue(msg.contains("трансфера"));
    }

    @Test
    void shouldResolveCompensationReasonAndUserMessage() {
        String reason = resolver.resolveCompensationReason(
                BookingType.FLIGHT,
                BookingType.HOTEL,
                "Номер недоступен"
        );
        assertTrue(reason.contains("компенсация бронирования"));
        assertTrue(reason.contains("авиабилета"));
        assertTrue(reason.contains("отеля"));
        assertTrue(reason.contains("Номер недоступен"));

        String userMsg = resolver.resolveCompensationUserMessage(
                BookingType.FLIGHT,
                BookingType.HOTEL
        );
        assertTrue(userMsg.contains("авиабилета"));
        assertTrue(userMsg.contains("отеля"));
        assertTrue(userMsg.contains("автоматически отменено"));
    }

    @Test
    void shouldResolveSkippedStepReasonAndUserMessage() {
        String reason = resolver.resolveSkippedStepReason(
                BookingType.TRANSFER,
                BookingType.HOTEL,
                "Номер недоступен"
        );
        assertTrue(reason.contains("не выполнялось"));
        assertTrue(reason.contains("трансфера"));
        assertTrue(reason.contains("отеля"));

        String userMsg = resolver.resolveSkippedStepUserMessage(
                BookingType.TRANSFER,
                BookingType.HOTEL
        );
        assertTrue(userMsg.contains("трансфера"));
        assertTrue(userMsg.contains("не выполнялось"));
    }

    @Test
    void shouldResolveManualCancellationReasonAndUserMessage() {
        String reason = resolver.resolveManualCancellationReason(BookingType.HOTEL);
        assertEquals("Отменено по запросу пользователя", reason);

        String userMsg = resolver.resolveManualCancellationUserMessage(BookingType.HOTEL);
        assertTrue(userMsg.contains("отеля"));
        assertTrue(userMsg.contains("по запросу пользователя"));
    }

    @Test
    void shouldResolveOrderFailureMessage() {
        String orderMsg = resolver.resolveOrderFailureMessage(BookingType.HOTEL, "Номер недоступен");
        assertTrue(orderMsg.contains("Заказ не может быть выполнен"));
        assertTrue(orderMsg.contains("отеля"));
        assertTrue(orderMsg.contains("успешно отменены"));
    }
}
