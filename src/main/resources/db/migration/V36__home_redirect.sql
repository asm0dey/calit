-- Bean calit-q8m1 / GH #193: a signed-in GET / now redirects to /me. Per-user opt-out.
--
-- Deliberate departure from the rule V33 states in its own comment ("TRUE for existing rows
-- preserves exactly the behaviour they have today"). Today's behaviour is NO redirect, so
-- preserving it would mean backfilling FALSE -- which ships an opt-in to every user who already
-- exists and leaves the problem in place on every current instance. The V33 rule guards against
-- silent surprises that are hard to notice and hard to undo; landing on your own dashboard is
-- noticed instantly and undone by one checkbox or by /calit. Existing rows get TRUE on purpose.
ALTER TABLE owner_settings
    ADD COLUMN home_redirect_enabled BOOLEAN NOT NULL DEFAULT TRUE;
