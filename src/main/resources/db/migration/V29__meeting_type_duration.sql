-- The set of lengths a meeting type may be booked at, beyond its own duration_minutes,
-- which is an implicit member (ADR-0003). DDL only: an empty table means the set is
-- exactly {duration_minutes}, so every existing type is already valid.
create table meeting_type_duration (
    meeting_type_id       bigint not null references meeting_type (id) on delete cascade,
    duration_minutes      int    not null check (duration_minutes > 0),
    buffer_before_minutes int    null check (buffer_before_minutes >= 0),
    buffer_after_minutes  int    null check (buffer_after_minutes >= 0),
    primary key (meeting_type_id, duration_minutes)
);
