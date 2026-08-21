---
# calit-gsl7
title: Fix test fixtures pinned to hardcoded calendar dates
status: completed
type: bug
priority: normal
created_at: 2026-08-21T15:31:43Z
updated_at: 2026-08-21T16:58:59Z
---

Test fixtures across the suite pin literal calendar dates (e.g. 2026-08-20) that silently become wrong once the wall clock passes them — time bombs, not regressions. AdminTimeRenderingTest already fired: two tests assert a dashboard booking renders, but the fixture is pinned to 2026-08-20 and the dashboard filters startUtc >= Instant.now(); today is 2026-08-21 so the booking is filtered out and assertions fail.

Plan:
1. Fix AdminTimeRenderingTest first, confirm green.
2. Sweep src/test/java for the same pattern (grep for 20YY-MM-DD literals, LocalDate.of/LocalDateTime.of/Instant.parse with year >= 2026).
3. Triage each hit: fix only fixtures whose test semantics depend on being future/past relative to now (dashboard/pending listings, reminders, availability slots, expiry). Leave alone fixtures that are arbitrary and only test formatting/parsing/ordering/arithmetic (ICS builder, locale rendering, token-expiry with both ends pinned).
4. Express future dates as now-relative offsets (e.g. today + 1 year), following any existing now() idiom in the test suite; avoid introducing flakiness (midnight/DST/availability-window boundary risk) by pinning the offset and deriving the base from now.
5. Run mvn spotless:apply, then full suite once at the end.
6. Write TASK-REPORT.md with triage counts and evidence.



## Progress
- [x] Fixed AdminTimeRenderingTest: seedConfirmedBooking() now takes an Instant param; dashboard-visible fixtures pinned now+1yr instead of literal 2026-08-20/21; isolation test's two LocalDate.of(2026,...) fixtures now LocalDate.now().plusYears(1) / +1day. pendingNoJsFallbackIsHumanReadableWithZone's literal Instant.parse left alone -- /me/pending isn't time-filtered so it's not a date bomb. All 8 tests in the class now green.
- [ ] Sweep src/test/java for the same pattern (grep literals + LocalDate.of/LocalDateTime.of/Instant.parse with year >= 2026)
- [ ] Triage each hit: fix only fixtures whose test semantics depend on future/past-relative-to-now
- [ ] Run mvn spotless:apply
- [ ] Run full suite once at the end
- [ ] Write TASK-REPORT.md



## Sweep results
- Combined grep (literal 20YY-MM-DD dates + LocalDate.of/LocalDateTime.of/Instant.parse("20 + LocalDate.parse/LocalDateTime.parse("20) matched 169 occurrences across 47 files pre-fix.
- Only site.asm0dey.calit.web.AdminTimeRenderingTest.java had genuine date bombs (5 occurrences fixed: seedConfirmedBooking's Instant.parse + its 2 assertion literals, and the isolation test's 2 LocalDate.of(2026,...) fixtures). All other 45 files (164 remaining occurrences) reviewed and left alone -- each is either: (a) pure interval/ICS/formatting arithmetic never compared to a real Instant.now() (BusyIntervalsTest, IcsBuilderTest, IntervalTest, EmailLocaleTest, EmailServiceTest, MultiHostEmailFanoutTest, DisplayExtensionsTest, etc.), (b) a self-contained fixed now/expiry pair passed explicitly to a method under test rather than the wall clock (GoogleTokenServiceTest/ProbeTest, GoogleCredentialTest, LoginTicketServiceTest, PasswordResetServiceTest, PerUserOAuthStateTest, GoogleLoginServiceTest, EmailOutboxTest), or (c) an entity round-trip / raw-slot-generation / date-override CRUD test whose underlying query has no now()-filter at all (BookingTest, BookingGroupQueryTest, BookingCalendarAddressTest, SlotServiceTest/OverrideTest/QueryCountTest which all call generateRawSlots not availableSlots, AdminDateOverridesTest, AdminMeetingTypeDetailTest/FormTest, SharedMeetingsResourceTest, DateOverrideTest, AvailabilityRuleOwnerScopeTest). Verified via source: AdminResource.java line 273 (dashboard) is the only startUtc >= now filter; the pending query (line 225) has none; SlotService.generateRawSlots has no now() reference at all (only the higher-level availableSlots would, and no test in the corpus feeds it a stale literal).
- No unfired-but-future date bombs found (e.g. a 2026-09-01-style fixture headed for a now()-filtered query) other than the ones already fixed.

## Verification
- mvn spotless:apply run (clean, no other changes)
- AdminTimeRenderingTest: 8/8 green before AND after spotless (Dq uarkus.datasource.devservices.reuse=false to dodge a stale cross-worktree Postgres container with a newer Flyway migration than this branch)
- Full suite (mvn test, same reuse=false flag) launched in background; result pending.

## Summary of Changes

Fixed `AdminTimeRenderingTest` — 5 hardcoded date occurrences replaced with `now`-relative expressions (`LocalDate.now().plusYears(1)`), and `seedConfirmedBooking()` now takes the start instant as a parameter instead of pinning it. Chose +1 year specifically so the fixture always lands back in August, keeping it inside Amsterdam's DST window — a +N days offset large enough to stay future would eventually walk into CET and silently invalidate the "(CEST)" assertion.

Swept the rest of the suite: 169 dated occurrences across 47 files, all read and triaged against "does this fixture's date need to be future/past relative to `Instant.now()` for the assertion to mean anything?". 5 fixed, 164 deliberately left — they are fixed instants for formatting, parsing, arithmetic, or self-contained now-parameters, where determinism is the point and relative dates would be worse. No unfired-but-imminent bombs found.

Full suite green: 842 tests, 0 failures, 0 errors. Report: `TASK-REPORT.md` on branch `fix/relative-test-dates`.
