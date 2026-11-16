ALTER TABLE composite_orders
    ADD COLUMN failure_reason TEXT,
    ADD COLUMN user_message TEXT;

ALTER TABLE booking_steps
    ADD COLUMN error_code VARCHAR(100),
    ADD COLUMN error_message TEXT,
    ADD COLUMN user_message TEXT,
    ADD COLUMN error_details TEXT,
    ADD COLUMN cancellation_reason TEXT;

CREATE TABLE order_operation_history
(
    id                  UUID PRIMARY KEY,
    composite_order_id  UUID NOT NULL,
    booking_step_id     UUID,
    booking_type        VARCHAR(50),
    operation_type      VARCHAR(50) NOT NULL,
    status              VARCHAR(50) NOT NULL,
    http_status         INTEGER,
    error_code          VARCHAR(100),
    message             TEXT,
    user_message        TEXT,
    details             TEXT,
    cancellation_reason TEXT,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_operation_history_composite_order
        FOREIGN KEY (composite_order_id)
            REFERENCES composite_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_operation_history_booking_step
        FOREIGN KEY (booking_step_id)
            REFERENCES booking_steps (id)
            ON DELETE SET NULL
);

CREATE INDEX idx_operation_history_order_id
    ON order_operation_history (composite_order_id, created_at);

CREATE INDEX idx_operation_history_step_id
    ON order_operation_history (booking_step_id);
