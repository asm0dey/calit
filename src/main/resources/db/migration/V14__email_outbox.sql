-- Fallback transactional outbox. EmailService tries a direct mailer.send() first;
-- on SMTP failure the mail is parked here instead of being lost. OutboxScheduler
-- retries due rows (next_attempt_at <= now) with FOR UPDATE SKIP LOCKED -- the same
-- multi-node-safe, no-leader pattern as the reminder table.
CREATE TABLE email_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    recipient       VARCHAR(320) NOT NULL,        -- single To address
    subject         TEXT         NOT NULL,
    html_body       TEXT         NOT NULL,
    ics_bytes       BYTEA,                         -- optional single .ics attachment; NULL = none
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- due now on enqueue; NULL = dead (attempt-capped)
    sent_at         TIMESTAMPTZ,                   -- NULL = unsent
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The claim scan: WHERE sent_at IS NULL AND next_attempt_at <= now(). A NULL next_attempt_at
-- (dead row) is excluded by the index predicate, so dead rows never reappear in the claim.
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND next_attempt_at IS NOT NULL;
