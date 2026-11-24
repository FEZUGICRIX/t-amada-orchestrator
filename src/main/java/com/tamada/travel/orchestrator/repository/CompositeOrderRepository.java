package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompositeOrderRepository
        extends JpaRepository<CompositeOrder, UUID> {

    @Override
    @EntityGraph(attributePaths = {"bookingSteps", "passengers", "customerContact"})
    Optional<CompositeOrder> findById(UUID id);

    @EntityGraph(attributePaths = "bookingSteps")
    List<CompositeOrder> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            OrderStatus status,
            Instant cutoff
    );

    @EntityGraph(attributePaths = "bookingSteps")
    List<CompositeOrder> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            OrderStatus status,
            Instant cutoff,
            org.springframework.data.domain.Pageable pageable
    );

    @EntityGraph(attributePaths = "bookingSteps")
    List<CompositeOrder> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            List<OrderStatus> statuses,
            Instant cutoff
    );

    @EntityGraph(attributePaths = "bookingSteps")
    List<CompositeOrder> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            List<OrderStatus> statuses,
            Instant cutoff,
            org.springframework.data.domain.Pageable pageable
    );
}
