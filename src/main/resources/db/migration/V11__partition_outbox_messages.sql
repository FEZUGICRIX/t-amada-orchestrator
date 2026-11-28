-- Перевод таблицы outbox_messages на ежедневное RANGE-партиционирование по created_at (UTC)
-- для быстрой архивации и очистки старых событий через DETACH PARTITION CONCURRENTLY / DROP TABLE
-- без блокировок и раздувания таблицы (table bloat).

DROP VIEW IF EXISTS outbox;

ALTER TABLE outbox_messages RENAME TO outbox_messages_legacy;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'outbox_messages_pkey') THEN
        ALTER TABLE outbox_messages_legacy
            RENAME CONSTRAINT outbox_messages_pkey TO outbox_messages_legacy_pkey;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_outbox_messages_status_created_at;
DROP INDEX IF EXISTS idx_outbox_messages_aggregate;
DROP INDEX IF EXISTS idx_outbox_pending_created_at;

CREATE TABLE outbox_messages
(
    id             UUID NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    payload        TEXT NOT NULL,
    status         VARCHAR(50) NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    processed_at   TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_messages PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_outbox_messages_status_created_at
    ON outbox_messages (status, created_at);

CREATE INDEX idx_outbox_messages_aggregate
    ON outbox_messages (aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_pending_created_at
    ON outbox_messages (created_at ASC)
    WHERE status = 'PENDING';

CREATE SCHEMA IF NOT EXISTS outbox_archive;

DO $$
DECLARE
    utc_day   TIMESTAMP;
    day_start TIMESTAMPTZ;
    day_end   TIMESTAMPTZ;
    part_name TEXT;
    rec       RECORD;
BEGIN
    -- Создаём партиции для всех исторических дат, присутствующих в таблице
    FOR rec IN
        SELECT DISTINCT date_trunc('day', created_at AT TIME ZONE 'UTC') AS day_ts
        FROM outbox_messages_legacy
    LOOP
        day_start := rec.day_ts AT TIME ZONE 'UTC';
        day_end   := (rec.day_ts + INTERVAL '1 day') AT TIME ZONE 'UTC';
        part_name := 'outbox_messages_' || to_char(rec.day_ts, 'YYYY_MM_DD');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF outbox_messages FOR VALUES FROM (%L) TO (%L)',
            part_name, day_start, day_end
        );
    END LOOP;

    -- Предварительно создаём ежедневные партиции от вчерашнего дня на 14 дней вперёд (UTC)
    FOR i IN -1..14 LOOP
        utc_day   := date_trunc('day', now() AT TIME ZONE 'UTC') + make_interval(days => i);
        day_start := utc_day AT TIME ZONE 'UTC';
        day_end   := (utc_day + INTERVAL '1 day') AT TIME ZONE 'UTC';
        part_name := 'outbox_messages_' || to_char(utc_day, 'YYYY_MM_DD');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF outbox_messages FOR VALUES FROM (%L) TO (%L)',
            part_name, day_start, day_end
        );
    END LOOP;
END $$;

-- Страховочная партиция по умолчанию, чтобы вставка не падала, если планировщик ещё не создал партицию
CREATE TABLE outbox_messages_default PARTITION OF outbox_messages DEFAULT;

INSERT INTO outbox_messages (id, aggregate_type, aggregate_id, event_type, payload, status, created_at, processed_at)
SELECT id, aggregate_type, aggregate_id, event_type, payload, status, created_at, processed_at
FROM outbox_messages_legacy;

DROP TABLE outbox_messages_legacy;

CREATE VIEW outbox AS SELECT * FROM outbox_messages;
