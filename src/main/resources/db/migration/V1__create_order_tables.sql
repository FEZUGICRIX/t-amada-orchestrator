CREATE TABLE composite_orders
(
    id         UUID PRIMARY KEY,
    status     VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE booking_steps
(
    id                 UUID PRIMARY KEY,
    composite_order_id UUID NOT NULL,
    type               VARCHAR(255) NOT NULL,
    status             VARCHAR(255) NOT NULL,
    external_id        VARCHAR(255),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_booking_steps_composite_order
        FOREIGN KEY (composite_order_id)
            REFERENCES composite_orders (id)
);

CREATE INDEX idx_booking_steps_composite_order_id
    ON booking_steps (composite_order_id);