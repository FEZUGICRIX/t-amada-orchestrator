ALTER TABLE hotel_rooms
    ADD COLUMN IF NOT EXISTS variant_id          VARCHAR(255),
    ADD COLUMN IF NOT EXISTS meal_type           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS cancellation_policy TEXT;
