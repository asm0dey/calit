---
# calit-yotg
title: CalDAV calendar support (busy check + event write)
status: completed
type: feature
priority: high
tags:
    - competitor-parity
created_at: 2026-10-16T22:05:40Z
updated_at: 2026-10-17T17:18:17Z
parent: calit-0arg
blocked_by:
    - calit-5fw0
    - calit-bgxr
---

Google is the only calendar today. CalDAV covers Nextcloud, Fastmail, iCloud (app password), mailbox.org, Radicale — what self-hosters run. Read free/busy and write the booking event. Build behind the existing CalendarPort/CalendarListPort. ADR 0001 (Google event is a mirror of the booking) must generalise to any calendar. Competitors: Cal.com, Zcal. Size: L.

## Summary of Changes

CalDAV calendars alongside Google on feat/caldav. V37 renames google_credential/google_calendar to calendar_account/calendar (single-table, provider discriminator) with filtered compatibility views and paired-column triggers; V38 adds booking.removed_mirror_provider. caldav package: SSRF-guarded CaldavHttp (HostGuard per hop, 10 MiB cap), CaldavClient (RFC 6764 discovery, REPORT, PUT/GET/DELETE, probe), BusyExtractor (ical4j, client-side recurrence, fail closed), CaldavCalendarPort. RoutingCalendarPort discovers ProviderCalendarPort beans by type. Invites: CalDAV Mirror events carry no attendees, calit sends the .ics (ADR 0012); no write calendar books without a Mirror event (ADR 0013); migration shims ADR 0014. /me/calendars page (connect, password, disconnect, None write option, no-write notice). Probe for CalDAV accounts; privacy export/erasure/policy; docs-site CalDAV page and changelog. Verified: mvn verify 1457 + CaldavFlowIT + OgImageResourceIT; native image (NIK 25.0.3) runs CaldavFlowIT. Follow-ups: calit-4air, calit-1cub, calit-cslr, calit-j3is, calit-eezt, calit-719z.
