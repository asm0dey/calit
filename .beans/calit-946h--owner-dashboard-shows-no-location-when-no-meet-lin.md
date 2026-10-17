---
# calit-946h
title: Owner dashboard shows no location when no Meet link was minted
status: todo
type: bug
priority: low
created_at: 2026-10-17T11:38:45Z
updated_at: 2026-10-17T11:38:45Z
---

AdminResource/dashboard.html:51 shows only b.meetLink; since calit-5fw0 a disconnected Creator's Meet-type bookings have no link and the dashboard shows nothing, while mail/confirmation fall back to locationDetail (ADR 0005).
