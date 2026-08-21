-- Backfill default availability for accounts that onboarded before the first-login wizard learned
-- to seed it (calit-sjwh). DefaultAvailabilitySeeder was dead code: its startup observer was a
-- no-op and weekdayDefaults() had no production caller, so every user created up to now has zero
-- global availability rules -- their meeting types offer no slots and the working-hours grid renders
-- empty under help text promising defaults. MeSetupResource#submit now seeds Mon-Fri 09:00-18:00 at
-- onboarding, but an already-onboarded account never re-enters the wizard, so it needs this.
--
-- Mirrors DefaultAvailabilitySeeder.seedGlobalDefaults: same hours, same global scope
-- (meeting_type_id IS NULL), same "skip an owner who already has ANY global rule" guard -- so
-- hand-set hours are never overwritten and re-running adds nothing.
--
-- Skips disabled owners: their public booking page is still served and still bookable, so
-- seeding one would hand out real Mon-Fri bookable time -- real bookings, real emails -- for an
-- account an admin deliberately switched off. This also restores fidelity with the Java path: a
-- disabled user can't authenticate (EnabledUserAugmentor), so seedGlobalDefaults could never run
-- for one there either.
INSERT INTO availability_rule (owner_id, day_of_week, start_time, end_time, meeting_type_id)
SELECT u.id, d.day_of_week, TIME '09:00', TIME '18:00', NULL
FROM app_user u
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'))
    AS d(day_of_week)
WHERE u.enabled AND NOT EXISTS (
    SELECT 1 FROM availability_rule r
    WHERE r.owner_id = u.id AND r.meeting_type_id IS NULL
);
