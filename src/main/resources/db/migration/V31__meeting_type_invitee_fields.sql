-- Per-type control over the built-in invitee fields on the booking form (GH #130). Email stays
-- mandatory and has no mode. Defaults reproduce today's form exactly, so existing types are untouched.
alter table meeting_type
    add column name_mode   varchar(16) not null default 'REQUIRED',
    add column guests_mode varchar(16) not null default 'OPTIONAL';
