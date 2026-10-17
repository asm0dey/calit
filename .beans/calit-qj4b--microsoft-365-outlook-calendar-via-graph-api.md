---
# calit-qj4b
title: Microsoft 365 / Outlook calendar via Graph API
status: draft
type: feature
priority: normal
tags:
    - competitor-parity
created_at: 2026-10-16T22:05:40Z
updated_at: 2026-10-17T13:02:24Z
parent: calit-0arg
---

Second-largest calendar audience. OAuth flow shaped like Google's; busy check + event write + Teams link optional. Reuse CalendarPort and the multi-account model. Competitors: Calendly, Cal.com, SavvyCal. Size: L.

## Storage: split calendar_account per provider first

Decided 2026-10-17 (during CalDAV, calit-yotg): calendar_account stays SINGLE_TABLE (provider discriminator + nullable per-provider columns) for Google + CalDAV. Adding Microsoft is the trigger to split it into JOINED per-provider tables (calendar_account common columns + google_account / caldav_account / microsoft_account extension tables). Do it as its own expand/contract migration AFTER the V37 compatibility views are dropped (a JOIN view isn't auto-updatable, so splitting while the shims exist would need INSTEAD OF triggers).
