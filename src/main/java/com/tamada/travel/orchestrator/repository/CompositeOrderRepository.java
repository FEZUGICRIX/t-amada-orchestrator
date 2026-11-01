package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.CompositeOrder;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface CompositeOrderRepository
        extends JpaRepository<CompositeOrder, UUID> {

    @Override
    @NonNull
    @EntityGraph(attributePaths = "bookingSteps")
    Optional<CompositeOrder> findById(@NonNull UUID id);
}
