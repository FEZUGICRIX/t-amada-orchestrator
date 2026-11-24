package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;

/**
 * Единый шлюз (Gateway) для взаимодействия со всеми внешними сервисами бронирования.
 * Позволяет вызывать операции бронирования и отмены любого типа услуги через одну точку входа.
 */
public interface BookingGateway {

    /**
     * Выполняет прямое бронирование услуги указанного типа.
     */
    BookingResponse book(BookingType type, BookingRequest request);

    /**
     * Выполняет компенсирующую отмену бронирования услуги указанного типа.
     */
    CancelBookingResponse cancel(BookingType type, CancelBookingRequest request);
}
