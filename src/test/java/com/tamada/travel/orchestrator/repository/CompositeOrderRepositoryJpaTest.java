package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.BookingType;
import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class CompositeOrderRepositoryJpaTest {

    @Autowired
    private CompositeOrderRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldFindStuckOrdersByStatusAndUpdatedAtBeforeInAscendingOrder() {
        CompositeOrder order1 = CompositeOrder.create();
        order1.addBookingStep(BookingType.FLIGHT, UUID.randomUUID());
        order1.startProcessing();
        entityManager.persist(order1);
        entityManager.flush();

        CompositeOrder order2 = CompositeOrder.create();
        order2.addBookingStep(BookingType.HOTEL, UUID.randomUUID());
        order2.startProcessing();
        entityManager.persist(order2);
        entityManager.flush();

        CompositeOrder recentOrder = CompositeOrder.create();
        recentOrder.addBookingStep(BookingType.INSURANCE, UUID.randomUUID());
        recentOrder.startProcessing();
        entityManager.persist(recentOrder);
        entityManager.flush();

        CompositeOrder completedOrder = CompositeOrder.create();
        completedOrder.addBookingStep(BookingType.TRANSFER, UUID.randomUUID());
        completedOrder.startProcessing();
        completedOrder.startNextBookingStep().orElseThrow().confirm("transfer-1");
        completedOrder.complete();
        entityManager.persist(completedOrder);
        entityManager.flush();

        Instant now = Instant.now();
        Instant time1 = now.minus(Duration.ofMinutes(10));
        Instant time2 = now.minus(Duration.ofMinutes(5));
        Instant timeRecent = now.minus(Duration.ofSeconds(30));
        Instant timeCompleted = now.minus(Duration.ofMinutes(10));

        // Напрямую обновляем updated_at в БД, чтобы обойти автоматический JPA Auditing
        jdbcTemplate.update("UPDATE composite_orders SET updated_at = ? WHERE id = ?", time1, order1.getId());
        jdbcTemplate.update("UPDATE composite_orders SET updated_at = ? WHERE id = ?", time2, order2.getId());
        jdbcTemplate.update("UPDATE composite_orders SET updated_at = ? WHERE id = ?", timeRecent, recentOrder.getId());
        jdbcTemplate.update("UPDATE composite_orders SET updated_at = ? WHERE id = ?", timeCompleted, completedOrder.getId());

        entityManager.clear();

        Instant cutoff = now.minus(Duration.ofMinutes(2));
        List<CompositeOrder> stuck = repository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                OrderStatus.PROCESSING,
                cutoff
        );

        assertEquals(2, stuck.size());
        assertEquals(order1.getId(), stuck.get(0).getId());
        assertEquals(order2.getId(), stuck.get(1).getId());
    }
}
