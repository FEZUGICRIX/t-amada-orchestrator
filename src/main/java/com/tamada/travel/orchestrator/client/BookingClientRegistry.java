package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.client.dto.BookingRequest;
import com.tamada.travel.orchestrator.client.dto.BookingResponse;
import com.tamada.travel.orchestrator.client.dto.CancelBookingRequest;
import com.tamada.travel.orchestrator.client.dto.CancelBookingResponse;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Реестр и единый шлюз клиентских адаптеров бронирования.
 * Реализует {@link BookingGateway}, предоставляя единую точку вызова операций book и cancel
 * без необходимости вручную разрешать конкретный клиент во внешнем коде.
 */
@Component
public class BookingClientRegistry implements BookingGateway {

    private final Map<BookingType, BookingClient> clients;

    public BookingClientRegistry(List<BookingClient> clients) {
        this.clients = clients.stream()
                .collect(toUnmodifiableMap(
                        BookingClient::type,
                        Function.identity()
                ));
    }

    public BookingClient getClient(BookingType type) {
        BookingClient client = clients.get(type);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No booking client configured for type " + type
            );
        }
        return client;
    }

    @Override
    public BookingResponse book(BookingType type, BookingRequest request) {
        return getClient(type).book(request);
    }

    @Override
    public CancelBookingResponse cancel(BookingType type, CancelBookingRequest request) {
        return getClient(type).cancel(request);
    }
}
