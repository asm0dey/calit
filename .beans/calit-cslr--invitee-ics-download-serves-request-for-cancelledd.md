---
# calit-cslr
title: Invitee .ics download serves REQUEST for cancelled/declined bookings
status: todo
type: bug
created_at: 2026-10-17T10:57:00Z
updated_at: 2026-10-17T10:57:00Z
---

PublicResource ics download route (~:674-683) serves a REQUEST/CONFIRMED .ics even for CANCELLED/DECLINED bookings; since calit-bgxr it carries the same SEQUENCE as the CANCEL, so importing it after a cancel resurrects the event. Fix: 404 or serve METHOD:CANCEL for non-active bookings. Found in calit-bgxr final review.
