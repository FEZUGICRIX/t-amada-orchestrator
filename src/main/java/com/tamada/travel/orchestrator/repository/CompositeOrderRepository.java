package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.CompositeOrder;
import com.tamada.travel.orchestrator.model.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompositeOrderRepository
        extends JpaRepository<CompositeOrder, UUID> {

    @Override
    @NonNull
    @EntityGraph(attributePaths = "bookingSteps")
    Optional<CompositeOrder> findById(@NonNull UUID id);

    @EntityGraph(attributePaths = "bookingSteps")
    List<CompositeOrder> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            @NonNull OrderStatus status,
            @NonNull Instant cutoff
    );
}
