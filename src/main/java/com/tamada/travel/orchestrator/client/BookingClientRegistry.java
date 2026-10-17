package com.tamada.travel.orchestrator.client;

import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.toUnmodifiableMap;

@Component
public class BookingClientRegistry {

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
}
