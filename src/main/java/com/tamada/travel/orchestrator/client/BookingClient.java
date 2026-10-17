package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;

public interface BookingClient {

    BookingType type();

    BookingResponse book(BookingRequest request);
}
