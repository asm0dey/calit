---
# calit-ltdx
title: 'Task 8: Owner data export'
status: completed
type: task
priority: normal
created_at: 2026-09-16T17:09:29Z
updated_at: 2026-09-16T17:10:13Z
parent: calit-l3fk
---

GET /me/export — owner's whole subtree as one native json_build_object/json_agg query (R14), secrets redacted; PrivacyService.exportBooking rewritten the same way



## Summary of Changes

- `PrivacyService.exportOwner(Long ownerId)` (new): one native `json_build_object`/`json_agg` query (R14) covering the owner subtree — `exportedAt`, `account`, `settings`, `meetingTypes`, `availability`, `dateOverrides`, `bookings` (with nested guest emails), `notificationChannels`, `googleAccounts`. Every subquery filters by `:ownerId`. Secrets excluded by column list: never `password_hash`/`access_token`/`refresh_token`/OAuth state; `account` reports only `hasPassword`/`linkedGoogle`/`linkedOidc` booleans; notification channel `url` is the literal `[redacted]` (bound param, not string-concatenated). Empty collections coalesce to `[]`, missing settings row to `{}`.
- `PrivacyService.exportBooking(String manageToken)` (rewritten per R14, was Task 6 Map-building code): same pattern, keyed by `manage_token` with `erased_at IS NULL`; throws the original `NotFoundException` (token-free message) when no row matches. `meetingType` reproduces `Booking.effectiveTitle` in SQL (`CASE WHEN nullif(trim(title), '') IS NOT NULL THEN title ELSE mt.name END`). Guests export only `{email, status}`.
- Both methods return the query's `::text` cast as a plain Java `String`. Verified (read Quarkus's `BasicServerJacksonMessageBodyWriter` source) that quarkus-rest-jackson special-cases `String` entities and writes them byte-for-byte instead of re-encoding as a JSON string literal, so `Response.ok(jsonString)` under `@Produces(APPLICATION_JSON)` serves the raw JSON verbatim — no extra quoting/escaping.
- Removed now-unused `Map`/`LinkedHashMap`/`MeetingType`/`BookingGuest` imports from `PrivacyService` (the old Map-building `exportBooking` was their only user).
- `AdminResource`: `GET /me/export` — `application/json` attachment, owner-scoped via `currentOwner.id()`, no parameter. Link added to `settings.html`'s existing Task-7 footer block, alongside "Delete my account".
- i18n: `AdminMessages.adm_settings_export_link` ("Download all my data") + German (`adm_de.properties`); added to `HE_DEFERRED_ADMIN_KEYS` per R10 (Hebrew deferred for this epic, same as every other Task 6-8 GDPR string).
- New test helper `ChannelFixtures.seedChannel(ownerId, url)` beside `ErasureFixtures` (self-wraps `QuarkusTransaction.requiringNew()`, mirrors `MultiHostFixtures.channel` but callable directly from a test method body).
- `OwnerExportTest` (new, 5 tests): the brief's 4 (anonymous-401 test adjusted to the codebase's real behavior — see below) plus a 5th verifying a second owner's booking (unique invitee name) never appears in the admin's export.
  - Anonymous-access test: confirmed empirically (ran the test) that this codebase's form-auth challenge for an unauthenticated `/me*` request is a 302 redirect to `/login`, not a bare 401 — consistent with `ReservedRouteTest`'s existing `anyOf(302, 303, 401)` assertion for plain `/me`. Asserted the real behavior (302 + Location containing `/login`) rather than the brief's literal 401.
- `BookingExportTest` (Task 6, unmodified) still passes unchanged against the rewritten `exportBooking`.
- Full suite: 1196/1196, `BUILD SUCCESS`.
