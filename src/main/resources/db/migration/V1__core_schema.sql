CREATE TABLE owner_settings (
    id          BIGINT       PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    owner_email VARCHAR(255) NOT NULL,
    timezone    VARCHAR(64)  NOT NULL
);

CREATE TABLE meeting_type (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    slug                  VARCHAR(255) NOT NULL UNIQUE,
    duration_minutes      INT          NOT NULL,
    buffer_before_minutes INT          NOT NULL DEFAULT 0,
    buffer_after_minutes  INT          NOT NULL DEFAULT 0,
    description           TEXT,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    secret                BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE availability_rule (
    id              BIGSERIAL   PRIMARY KEY,
    day_of_week     VARCHAR(16) NOT NULL,
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    meeting_type_id BIGINT      REFERENCES meeting_type(id) ON DELETE CASCADE
);

CREATE INDEX idx_availability_lookup ON availability_rule (meeting_type_id, day_of_week);

-- Owner-defined EXTRA booking-form fields. Full name + email are always-present
-- built-ins (Booking.invitee_name / invitee_email), so they are NOT rows here.
CREATE TABLE booking_field (
    id              BIGSERIAL    PRIMARY KEY,
    meeting_type_id BIGINT       REFERENCES meeting_type(id) ON DELETE CASCADE,
    field_key       VARCHAR(64)  NOT NULL,
    label           VARCHAR(255) NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    required        BOOLEAN      NOT NULL DEFAULT FALSE,
    position        INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_booking_field_scope ON booking_field (meeting_type_id, position);

-- Default global form has one optional "description" field out of the box.
INSERT INTO booking_field (meeting_type_id, field_key, label, type, required, position)
VALUES (NULL, 'description', 'Description', 'LONG_TEXT', FALSE, 0);
