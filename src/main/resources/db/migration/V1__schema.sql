CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE service_type (
    id               BIGINT       PRIMARY KEY,
    name             VARCHAR(100) NOT NULL UNIQUE,
    duration_minutes INT          NOT NULL CHECK (duration_minutes > 0),
    required_skill   VARCHAR(32)  NOT NULL
);

CREATE TABLE zone (
    id   BIGINT       PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

-- A pincode belongs to exactly one zone.
CREATE TABLE zone_pincode (
    pincode VARCHAR(6) PRIMARY KEY CHECK (pincode ~ '^[0-9]{6}$'),
    zone_id BIGINT     NOT NULL REFERENCES zone (id)
);

CREATE TABLE technician (
    id          BIGINT       PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    zone_id     BIGINT       NOT NULL REFERENCES zone (id),
    shift_start TIME         NOT NULL,
    shift_end   TIME         NOT NULL,
    CHECK (shift_start < shift_end)
);

CREATE INDEX technician_zone_idx ON technician (zone_id);

CREATE TABLE technician_skill (
    technician_id BIGINT      NOT NULL REFERENCES technician (id),
    skill         VARCHAR(32) NOT NULL,
    PRIMARY KEY (technician_id, skill)
);

CREATE TABLE booking (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_phone      VARCHAR(16)  NOT NULL,
    service_id          BIGINT       NOT NULL REFERENCES service_type (id),
    pincode             VARCHAR(6)   NOT NULL REFERENCES zone_pincode (pincode),
    technician_id       BIGINT       NOT NULL REFERENCES technician (id),
    start_time          TIMESTAMPTZ  NOT NULL,
    end_time            TIMESTAMPTZ  NOT NULL,
    -- end_time + travel buffer. Stored (not computed) because index expressions must be
    -- immutable and timestamptz + interval is not; it also freezes the buffer that applied
    -- when the booking was made.
    blocked_until       TIMESTAMPTZ  NOT NULL,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED')),
    idempotency_key     VARCHAR(255),
    request_fingerprint VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    cancelled_at        TIMESTAMPTZ,
    CHECK (end_time > start_time),
    CHECK (blocked_until >= end_time),
    CONSTRAINT booking_idempotency_key_uq UNIQUE (idempotency_key),
    -- Last line of defence against double-booking: no two live bookings of the same
    -- technician may have overlapping [start, end + buffer) ranges. Cancelled rows drop out
    -- of the constraint, so their time is free again the moment the cancel commits.
    CONSTRAINT booking_no_overlap EXCLUDE USING gist (
        technician_id WITH =,
        tstzrange(start_time, blocked_until, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED')
);

CREATE INDEX booking_technician_start_idx ON booking (technician_id, start_time);
CREATE INDEX booking_phone_start_idx ON booking (customer_phone, start_time DESC);
