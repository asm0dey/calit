---
# calit-s5mh
title: declineGuest drops Co-hosts from a group's Google event
status: todo
type: bug
priority: normal
created_at: 2026-10-17T11:38:45Z
updated_at: 2026-10-17T11:38:45Z
---

BookingService.declineGuest (~1478-1488) re-syncs attendees with attendeeEmails(booking, owner) (invitee, Creator, guests) instead of groupAttendeeEmails, so one guest declining removes every Co-host from the group Mirror event. Pre-existing; since calit-5fw0 all group events live on the lead, so it affects every group. Found in calit-5fw0 final review (code reading, not reproduced).
