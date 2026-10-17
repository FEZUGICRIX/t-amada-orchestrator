ALTER TABLE booking_steps
    ADD COLUMN resource_id UUID;

UPDATE booking_steps
SET resource_id = gen_random_uuid()
WHERE resource_id IS NULL;

ALTER TABLE booking_steps
    ALTER COLUMN resource_id SET NOT NULL;
