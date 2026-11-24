package com.tamada.travel.orchestrator.model;

public enum OutboxStatus {
    PENDING,
    SENT,
    PROCESSED,
    FAILED,
    DEAD_LETTER
}
