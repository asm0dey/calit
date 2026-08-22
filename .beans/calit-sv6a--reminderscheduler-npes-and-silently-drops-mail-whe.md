---
# calit-sv6a
title: ReminderScheduler NPEs and silently drops mail when an owner has no settings row
status: completed
type: bug
priority: low
created_at: 2026-08-22T14:33:36Z
updated_at: 2026-08-22T15:38:33Z
---

Surfaced while verifying the `fix/bug-sweep` branch. NOT introduced by it — `ReminderScheduler` and
its tests are untouched by that branch; the suite has been logging this all along.

`ReminderScheduler` reads the owner's settings unguarded, so a booking whose owner has no
`owner_settings` row throws `NullPointerException: Cannot invoke "OwnerSettings.getTimezone()"
because "owner" is null`. It is caught by the deliberate fail-soft at `ReminderScheduler.java:160-162`:

    } catch (Exception ex) {
        Log.errorf(ex, "reminder enqueue failed for booking %d (marked sent, mail dropped)", r.bookingId);
    }

so the row is marked sent and the reminder is dropped. The invitee never gets their reminder and
nothing surfaces beyond an ERROR line in the log.

This is the failure mode `calit-a4yj` predicted: "the next path that reads settings before onboarding
completes hits the NPE." a4yj closed the *creation* side — all five account-creation paths now seed
the row via `OwnerSettings.seed` — so a genuinely new user can no longer be in this state, and
`V24__backfill_owner_settings` handled the rows that predated it. What remains is the unguarded READ,
which is one bad row away from silently eating reminders.

## Todo

- [ ] Decide the guard: skip the reminder with a WARN, or fall back to UTC like `DisplayExtensions` does
- [ ] Consider whether `catch (Exception)` around the whole enqueue is too wide — it currently cannot
      distinguish "this owner is misconfigured" from "the mailer is down", and marks sent either way
- [ ] Audit the other unguarded `OwnerSettings.forOwner(...)` reads named in calit-4whp for the same shape

## Summary of Changes

Fixed on `fix/bug-sweep` (commit 12ab439), folded into PR #148 rather than deferred.

`EmailService.read` now returns null when the owner has no settings row — the same "nothing to
send" path every caller already takes for a missing booking — and logs a WARN naming the owner.
The NPE no longer reaches `ReminderScheduler`'s catch, so a reminder is no longer marked sent and
silently dropped.

Answers the bean's todos:
- **Guard shape**: skip-with-WARN, not UTC-fallback. With no settings row there is no owner address
  to send the owner copy to, so there is genuinely no mail to build.
- **Is `catch (Exception)` too wide?** Left alone. It is deliberate and documented — it exists so
  one poison booking cannot roll back the other 49 in the batch. The defect was the unguarded read
  upstream of it, not the catch.
- **Audit the other unguarded reads**: the two highest-blast-radius ones are now guarded with
  `OwnerSettings.coerceZone` — `EmailService.read` (backs every mail path) and
  `PublicResource.renderManage` (the invitee hub). Both write paths were already coerced by
  calit-4whp, so remaining read sites cannot see a bad value for any row written since.
