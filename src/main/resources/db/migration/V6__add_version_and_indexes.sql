ALTER TABLE composite_orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE booking_steps
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_composite_orders_status_created_at
    ON composite_orders (status, created_at);
