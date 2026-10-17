---
# calit-1cub
title: CANCEL .ics should bump SEQUENCE (invitee and guest)
status: todo
type: bug
priority: low
created_at: 2026-10-17T10:57:00Z
updated_at: 2026-10-17T10:57:00Z
---

RFC 5546 counts a STATUS change as a significant revision: a CANCEL should carry SEQUENCE > the last REQUEST. Today invitee (EmailService handleCancelled/deliverDeclined) and guest (guestIcs) CANCELs reuse booking.icsSequence unchanged. Major clients accept equal-sequence CANCEL, so low priority. Fix both paths together. Found in calit-bgxr final review.
