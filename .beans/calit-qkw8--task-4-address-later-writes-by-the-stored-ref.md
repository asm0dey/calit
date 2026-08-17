---
# calit-qkw8
title: 'Task 4: Address later writes by the stored ref'
status: completed
type: task
priority: normal
created_at: 2026-08-16T23:21:43Z
updated_at: 2026-08-17T08:28:10Z
---

Replace literal null CalendarRef args at BookingService update/delete sites with booking.calendarRef(); carry the ref through reschedule's re-approval path; clear address alongside eventId everywhere it's cleared; collapse groupEventId/organizerOwnerOf into groupEventRow. Part of calit-rma2 (rotating-the-google-write-target-orphans-existing).

DONE — commits 02c6605 (implementation) + 1e8c3ef (test pinning the reschedule delete to the stored calendar, with RED evidence). Reviewed clean by the task reviewer and re-reviewed after the fix.
