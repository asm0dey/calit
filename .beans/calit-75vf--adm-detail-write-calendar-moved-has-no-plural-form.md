---
# calit-75vf
title: adm_detail_write_calendar_moved has no plural form
status: completed
type: bug
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-22T12:45:12Z
---

The notice reads "1 upcoming bookings stay on the calendar they were created on" when the count is 1. Ships in en, de and he.

The codebase does no i18n pluralization anywhere, so this is a codebase-wide gap rather than a defect of [[calit-bh5t]] — but that feature ships a new, user-visible instance of it in three locales.

- [x] Decide whether to add a pluralization mechanism or reword the message to read correctly at any count (e.g. "upcoming bookings that stay: 1") -- decided: reword (a plural selector would cost a mechanism, a convention, and three locales worth of grammar rules -- Hebrew has its own plural forms -- to fix one string; putting the count last avoids all of that)
- [x] Apply the same treatment to any other counted message -- grep `{count}\|(int count)\|(long count)` across src/main/java/site/asm0dey/calit/i18n/ found 4 counted messages total: adm_detail_write_calendar_moved (reworded above), adm_shared_revokeConfirm_count and adm_hosts_removeConfirm_count (both phrased "{count} upcoming booking(s)" -- the parenthetical (s) already reads correctly at any count, so no change needed), and adm_hosts_error_cap ("at most {max} hosts" -- a limit, not a subject noun, already count-agnostic). No third message with a noun that disagrees with the count was found.

## Summary of Changes

Reworded `adm_detail_write_calendar_moved` in English, German and Hebrew to put the `{count}` placeholder last ("Upcoming bookings that stay ... : {count}"), so it reads correctly at every count without needing a pluralization mechanism. Added `AdminWriteCalendarTest#theStayBehindNoticeReadsCorrectlyAtCountOne`, which asserts the new phrasing and the absence of "1 upcoming bookings" at count 1; the existing `movingATypeWithUpcomingBookingsSaysTheyStayBehind` and `SharedWriteCalendarTest` assertions still pass unchanged. The grep for other counted messages found two more using "{count} ... booking(s)", already count-agnostic via the parenthetical, so no further reword was needed.
