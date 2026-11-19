CREATE INDEX idx_composite_orders_status_updated_at
    ON composite_orders (status, updated_at);
