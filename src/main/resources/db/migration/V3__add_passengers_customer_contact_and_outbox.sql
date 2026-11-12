CREATE TABLE passengers
(
    id                 UUID PRIMARY KEY,
    composite_order_id UUID NOT NULL,
    type               VARCHAR(50) NOT NULL,
    first_name         VARCHAR(255) NOT NULL,
    last_name          VARCHAR(255) NOT NULL,
    birth_date         DATE NOT NULL,
    citizenship        VARCHAR(100) NOT NULL,
    document_type      VARCHAR(100) NOT NULL,
    document_number    VARCHAR(100) NOT NULL,
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_passengers_composite_order
        FOREIGN KEY (composite_order_id)
            REFERENCES composite_orders (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_passengers_composite_order_id
    ON passengers (composite_order_id);

CREATE TABLE customer_contacts
(
    id                 UUID PRIMARY KEY,
    composite_order_id UUID NOT NULL UNIQUE,
    phone              VARCHAR(50) NOT NULL,
    email              VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_customer_contacts_composite_order
        FOREIGN KEY (composite_order_id)
            REFERENCES composite_orders (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_customer_contacts_composite_order_id
    ON customer_contacts (composite_order_id);

CREATE TABLE outbox_messages
(
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    payload        TEXT NOT NULL,
    status         VARCHAR(50) NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    processed_at   TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_outbox_messages_status_created_at
    ON outbox_messages (status, created_at);

CREATE INDEX idx_outbox_messages_aggregate
    ON outbox_messages (aggregate_type, aggregate_id);

CREATE VIEW customer_contact AS SELECT * FROM customer_contacts;
CREATE VIEW outbox AS SELECT * FROM outbox_messages;
