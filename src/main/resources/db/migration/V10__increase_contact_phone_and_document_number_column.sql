ALTER TABLE customer_contacts
    ALTER COLUMN phone TYPE VARCHAR(255);

ALTER TABLE passengers
    ALTER COLUMN document_number TYPE VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_outbox_pending_created_at
    ON outbox_messages (created_at ASC)
    WHERE status = 'PENDING';
