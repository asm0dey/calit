---
# calit-qkw8
title: 'Task 4: Address later writes by the stored ref'
status: in-progress
type: task
created_at: 2026-08-16T23:21:43Z
updated_at: 2026-08-16T23:21:43Z
---

Replace literal null CalendarRef args at BookingService update/delete sites with booking.calendarRef(); carry the ref through reschedule's re-approval path; clear address alongside eventId everywhere it's cleared; collapse groupEventId/organizerOwnerOf into groupEventRow. Part of calit-rma2 (rotating-the-google-write-target-orphans-existing).
