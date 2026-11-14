package com.tamada.travel.orchestrator.controller;

import com.tamada.travel.orchestrator.dto.BookingStepResponse;
import com.tamada.travel.orchestrator.dto.CustomerContactResponse;
import com.tamada.travel.orchestrator.dto.HotelRoomResponse;
import com.tamada.travel.orchestrator.dto.OrderResponse;
import com.tamada.travel.orchestrator.dto.PassengerResponse;
import com.tamada.travel.orchestrator.exception.InvalidBookingDataException;
import com.tamada.travel.orchestrator.exception.OrderNotFoundException;
import com.tamada.travel.orchestrator.model.BookingStatus;
import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.OrderStatus;
import com.tamada.travel.orchestrator.model.PassengerType;
import com.tamada.travel.orchestrator.service.CompositeOrderService;
import com.tamada.travel.orchestrator.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompositeOrderController.class)
class CompositeOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompositeOrderService service;

    @Test
    void shouldCreateOrder() throws Exception {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of(new BookingStepResponse(
                        stepId,
                        BookingType.FLIGHT,
                        resourceId,
                        BookingStatus.PENDING,
                        null)));

        when(service.createOrder(any()))
                .thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s"
                                    }
                                  ]
                                }
                                """.formatted(resourceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.bookingSteps[0].resourceId")
                        .value(resourceId.toString()));
    }

    @Test
    void shouldCreateOrderWithPricesAndReturnPricingFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                new BigDecimal("10000.00"),
                new BigDecimal("67.00"),
                new BigDecimal("10067.00"),
                List.of(new BookingStepResponse(
                        stepId,
                        BookingType.FLIGHT,
                        resourceId,
                        BookingStatus.PENDING,
                        null,
                        new BigDecimal("10000.00"))),
                List.of(),
                null);

        when(service.createOrder(any())).thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s",
                                      "price": 10000.00
                                    }
                                  ]
                                }
                                """.formatted(resourceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.basePrice").value(10000.00))
                .andExpect(jsonPath("$.serviceFee").value(67.00))
                .andExpect(jsonPath("$.totalPrice").value(10067.00))
                .andExpect(jsonPath("$.bookingSteps[0].price").value(10000.00));
    }

    @Test
    void shouldRejectOrderWithNegativePrice() throws Exception {
        UUID resourceId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s",
                                      "price": -50.00
                                    }
                                  ]
                                }
                                """.formatted(resourceId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldCreateOrderWithPassengersAndCustomerContact() throws Exception {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID passengerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of(new BookingStepResponse(
                        stepId,
                        BookingType.FLIGHT,
                        resourceId,
                        BookingStatus.PENDING,
                        null)),
                List.of(new PassengerResponse(
                        passengerId,
                        PassengerType.ADULT,
                        "Иван",
                        "Иванов",
                        LocalDate.of(1990, 5, 15),
                        "Россия",
                        "Паспорт РФ",
                        "4510 123456")),
                new CustomerContactResponse(contactId, "+79991234567", "ivanov@example.com")
        );

        when(service.createOrder(any())).thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s"
                                    }
                                  ],
                                  "passengers": [
                                    {
                                      "type": "ADULT",
                                      "first_name": "Иван",
                                      "last_name": "Иванов",
                                      "birth_date": "1990-05-15",
                                      "citizenship": "Россия",
                                      "document_type": "Паспорт РФ",
                                      "document_number": "4510 123456"
                                    }
                                  ],
                                  "customer_contact": {
                                    "phone": "+79991234567",
                                    "email": "ivanov@example.com"
                                  }
                                }
                                """.formatted(resourceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.passengers[0].firstName").value("Иван"))
                .andExpect(jsonPath("$.customerContact.phone").value("+79991234567"));
    }

    @Test
    void shouldCreateOrderViaShortOrdersPath() throws Exception {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of(new BookingStepResponse(
                        stepId,
                        BookingType.FLIGHT,
                        resourceId,
                        BookingStatus.PENDING,
                        null)));

        when(service.createOrder(any())).thenReturn(response);

        mockMvc.perform(
                post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "%s"
                                    }
                                  ]
                                }
                                """.formatted(resourceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void shouldRejectEmptyBookings() throws Exception {
        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(
                        jsonPath("$.validationErrors.bookings").exists());
    }

    @Test
    void shouldRejectNullBookings() throws Exception {
        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldRejectNullResourceId() throws Exception {
        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": null
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath(
                        "$['validationErrors']['bookings[0].resourceId']").exists());
    }

    @Test
    void shouldRejectNullBookingType() throws Exception {
        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": null,
                                      "resourceId": "00000000-0000-0000-0000-000000000001"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath(
                        "$['validationErrors']['bookings[0].type']").exists());
    }

    @Test
    void shouldRejectUnknownBookingType() throws Exception {
        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "PLANE",
                                      "resourceId": "00000000-0000-0000-0000-000000000001"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid request body"));
    }

    @Test
    void shouldReturnBadRequestForDuplicates() throws Exception {
        when(service.createOrder(any()))
                .thenThrow(
                        new InvalidBookingDataException(
                                "Booking types must not contain duplicates"));

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "00000000-0000-0000-0000-000000000001"
                                    },
                                    {
                                      "type": "FLIGHT",
                                      "resourceId": "00000000-0000-0000-0000-000000000002"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Booking types must not contain duplicates"));
    }

    @Test
    void shouldGetOrder() throws Exception {
        UUID id = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                List.of());

        when(service.getOrder(id))
                .thenReturn(response);

        mockMvc.perform(
                get("/api/v1/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void shouldReturn404WhenOrderNotFound() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.getOrder(id))
                .thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(
                get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void shouldRejectInvalidUuid() throws Exception {
        mockMvc.perform(
                get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value(
                                "Invalid value for parameter 'id'. Expected type: UUID"));
    }

    @Test
    void shouldStartOrder() throws Exception {
        UUID id = UUID.randomUUID();

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.PROCESSING,
                Instant.parse("2026-10-08T10:00:00Z"),
                Instant.parse("2026-10-08T10:00:00Z"),
                List.of());

        when(service.startOrder(id))
                .thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders/{id}/start", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void shouldReturnConflictWhenOrderCannotBeStarted() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.startOrder(id))
                .thenThrow(
                        new InvalidStateTransitionException(
                                "Cannot start order processing from status PROCESSING"));

        mockMvc.perform(
                post("/api/v1/orders/{id}/start", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldCancelOrder() throws Exception {
        UUID id = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.CANCELLED,
                Instant.parse("2026-10-11T10:00:00Z"),
                Instant.parse("2026-10-11T10:00:00Z"),
                List.of());

        when(service.cancelOrder(id))
                .thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void shouldReturnNotFoundWhenCancellingMissingOrder() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.cancelOrder(id))
                .thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(
                post("/api/v1/orders/{id}/cancel", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void shouldReturnConflictWhenOrderCannotBeCancelled() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.cancelOrder(id))
                .thenThrow(new InvalidStateTransitionException(
                        "Cannot cancel order manually from status NEW"));

        mockMvc.perform(
                post("/api/v1/orders/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldCreateOrderWithMultipleHotelRooms() throws Exception {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID hotelId = UUID.randomUUID();
        UUID room1Id = UUID.randomUUID();
        UUID room2Id = UUID.randomUUID();

        List<HotelRoomResponse> roomResponses = List.of(
                new HotelRoomResponse(room1Id, "Люкс", "С завтраком", new BigDecimal("12000.00"), "Завтрак включен"),
                new HotelRoomResponse(room2Id, "Стандарт", "Базовый", new BigDecimal("8000.00"), "Без отмены")
        );

        BookingStepResponse stepResponse = new BookingStepResponse(
                stepId,
                BookingType.HOTEL,
                hotelId,
                BookingStatus.PENDING,
                null,
                new BigDecimal("20000.00"),
                2,
                roomResponses
        );

        OrderResponse response = new OrderResponse(
                id,
                OrderStatus.NEW,
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"),
                new BigDecimal("20000.00"),
                new BigDecimal("134.00"),
                new BigDecimal("20134.00"),
                List.of(stepResponse),
                List.of(),
                null
        );

        when(service.createOrder(any())).thenReturn(response);

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "HOTEL",
                                      "resourceId": "%s",
                                      "roomsCount": 2,
                                      "rooms": [
                                        {
                                          "category": "Люкс",
                                          "tariff": "С завтраком",
                                          "price": 12000.00,
                                          "stayConditions": "Завтрак включен"
                                        },
                                        {
                                          "category": "Стандарт",
                                          "tariff": "Базовый",
                                          "price": 8000.00,
                                          "stayConditions": "Без отмены"
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(hotelId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.bookingSteps[0].type").value("HOTEL"))
                .andExpect(jsonPath("$.bookingSteps[0].roomsCount").value(2))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[0].category").value("Люкс"))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[0].tariff").value("С завтраком"))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[0].price").value(12000.00))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[0].stayConditions").value("Завтрак включен"))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[1].category").value("Стандарт"))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[1].tariff").value("Базовый"))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[1].price").value(8000.00))
                .andExpect(jsonPath("$.bookingSteps[0].rooms[1].stayConditions").value("Без отмены"))
                .andExpect(jsonPath("$.basePrice").value(20000.00))
                .andExpect(jsonPath("$.serviceFee").value(134.00))
                .andExpect(jsonPath("$.totalPrice").value(20134.00));
    }

    @Test
    void shouldReturnBadRequestWhenHotelRoomHasBlankCategory() throws Exception {
        UUID hotelId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "HOTEL",
                                      "resourceId": "%s",
                                      "rooms": [
                                        {
                                          "category": "  ",
                                          "tariff": "Базовый",
                                          "price": 8000.00
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(hotelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldReturnBadRequestWhenHotelRoomHasNegativePrice() throws Exception {
        UUID hotelId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookings": [
                                    {
                                      "type": "HOTEL",
                                      "resourceId": "%s",
                                      "rooms": [
                                        {
                                          "category": "Люкс",
                                          "tariff": "Базовый",
                                          "price": -500.00
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(hotelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
