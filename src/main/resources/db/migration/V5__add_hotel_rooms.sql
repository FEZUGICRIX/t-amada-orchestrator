CREATE TABLE hotel_rooms
(
    id              UUID PRIMARY KEY,
    booking_step_id UUID NOT NULL,
    category        VARCHAR(255) NOT NULL,
    tariff          VARCHAR(255) NOT NULL,
    price           NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    stay_conditions TEXT,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_hotel_rooms_booking_step
        FOREIGN KEY (booking_step_id)
            REFERENCES booking_steps (id)
            ON DELETE CASCADE
);

CREATE INDEX idx_hotel_rooms_booking_step_id
    ON hotel_rooms (booking_step_id);
