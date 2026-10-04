package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.CompositeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompositeOrderRepository
        extends JpaRepository<CompositeOrder, UUID> {
}