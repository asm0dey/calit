# Home is the instance entrance

`/` becomes the instance entrance. A signed-in visitor arriving at `/` is 303'd to `/me`
(`Cache-Control: no-store`); the marketing pitch that used to render there moves to a permanent
`/calit`, which renders identically for everyone and never redirects. `/` carries
`<link rel="canonical" href="/">` so the two URLs are not competing duplicates, and both of their
200 responses carry `Cache-Control: private` — the page varies by auth ("Signed in as X"), so a
shared cache must not be allowed to hand one visitor's render to another.

The redirect is a per-owner preference, `owner_settings.home_redirect_enabled`, defaulting on. A
user with no `OwnerSettings` row is not redirected, which fails toward the pre-existing behaviour.

The underlying principle: being signed in never redirects you away from a page that belongs to
someone. `/` belongs to the instance, so it may redirect; `/{username}` belongs to its owner and
never does — checking your own public page as a guest sees it is the main reason to visit it.

## Why `/calit`, not `/about`

`calit` is already in `Usernames.RESERVED`, alongside `index`, `privacy`, `terms`. `about` is not.
A literal `@Path("/about")` wins over `@Path("/{user}")` in JAX-RS, so on any existing instance
where someone had already registered the username `about`, their public booking page would
silently become the marketing page after upgrade, with nothing enforcing a changelog warning.
`/calit` costs no reserved-word change and cannot break an existing instance.

## Why existing rows are backfilled TRUE

`V33__notification_channel_default_enabled.sql` states the repo's own rule for a new boolean
column: "TRUE for existing rows preserves exactly the behaviour they have today." Applied
literally here it inverts — today's behaviour is no redirect, so preserving it would mean
backfilling FALSE, which ships a silent opt-in to every user who already exists and leaves the
friction this decision is meant to fix in place on every current instance.

Existing rows get TRUE deliberately, departing from the V33 rule on purpose. The V33 rule exists
to stop silent surprises that are hard to notice and hard to undo. Landing on your own dashboard
is the opposite of that: it is noticed on the very next visit, understood immediately, and undone
by one checkbox or by typing `/calit`.

## Considered options

**Close the issue.** Rejected: the friction is real, recurring, and universal — every account
holder on an instance, the operator included, pays a click or a typed `/me` on every visit, and
the admin shell has no link back to `/` to make the round trip any cheaper.

**`/?home` as the escape hatch.** Rejected: one URL carrying two meanings, disambiguated by a
query parameter nobody discovers, and the product page still has no linkable URL of its own.

**Redirect anonymous visitors to `/calit` too.** Rejected: the bare domain is what people paste
into chat and what carries the OG card. Redirecting it would break every link already shared to
`/`.

**Opt-in (default off).** Rejected: only helps people who go hunting in settings. The whole point
is that the friction is universal, not something most people would think to fix for themselves.

**A `LANDING_REDIRECT` env var.** Rejected: whether an instance is public-facing is already
handled by the anonymous branch (anonymous visitors always see the product page), so an
operator-wide knob would protect nothing this design does not already protect, while taking the
choice away from the individual owner.

**No preference at all.** Rejected, though defensible — `/calit` already works as a per-visit
opt-out. A persisted, per-owner choice was wanted instead of relying on everyone remembering the
escape hatch.

**Redirecting `/{username}` for its own owner.** Rejected: it breaks the one thing that page is
for. Checking your own public page as a guest sees it is the main reason an owner visits their own
landing, and a redirect there would remove that entirely.

## Consequences

- `PublicResource` gains the `/calit` route rendering the same template as `/`, and the `/` route
  gains the 303 branch, gated on `currentUser` and `OwnerSettings.homeRedirectEnabled`.
- `/calit` is exempt from `FirstRunRedirectFilter`, the same as `/`, so it keeps working during
  first-run setup.
- `V36__home_redirect.sql` adds `owner_settings.home_redirect_enabled BOOLEAN NOT NULL DEFAULT
  TRUE`; `OwnerSettings.homeRedirectEnabled` defaults `true` in code for the same reason.
- The new column is classified in `PersonalData.TABLES` as **not personal** — a UI preference, the
  same bucket as `ownerNotificationsEnabled` and `timeFormat` — so it sits outside GDPR exports and
  anonymisation. `PersonalDataInventoryTest` (see
  `docs/adr/0010-personal-data-is-a-hand-written-inventory-guarded-by-a-schema-test.md`) is what
  forced that classification the moment the migration landed.
- The admin shell brand in `adminBase.html` becomes an `<a href="/calit">` instead of a `<div>`,
  giving `/me` a link back out that did not exist before.
- `/me/settings` gets the opt-out checkbox, translated to `de` and `he` like every other
  user-facing string.
- Out of scope: what `/me` itself renders, and the `/{username}` landing — this decision only
  governs which page a visitor lands on, not what either destination looks like.
