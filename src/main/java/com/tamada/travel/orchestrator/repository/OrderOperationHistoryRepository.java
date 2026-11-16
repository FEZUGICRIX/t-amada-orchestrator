package com.tamada.travel.orchestrator.repository;

import com.tamada.travel.orchestrator.model.OrderOperationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderOperationHistoryRepository extends JpaRepository<OrderOperationHistory, UUID> {

    List<OrderOperationHistory> findByCompositeOrderIdOrderByCreatedAtAsc(UUID compositeOrderId);
}
