---
# calit-51x7
title: 'Booking sets: small UX, i18n and a11y follow-ups'
status: todo
type: task
priority: low
created_at: 2026-11-15T15:32:57Z
updated_at: 2026-11-15T15:32:57Z
parent: calit-ku6n
---

Follow-up from the multi-slot reviews (feat/multi-slot-booking).

- [ ] A 1..1 range saves as a no-op range (isMultiSlot false) — store as Off or refuse max < 2
- [ ] Conflict bounce shows the default-duration grid (POST lacks duration) — carry the viewed length
- [ ] Picker a11y: link each disabled slot to its reason via aria-describedby; aria-current on the chosen duration and on the current step
- [ ] Rail rows stay after an untick with JS on; on-load update() comment says bfcache (it is form-state restore)
- [ ] Sibling time labels on the manage page not localised without JS (DateTimeFormatter without locale — same as existing currentLabel)
- [ ] Declined / cancelled / guest notice bodies show the lead's time above the session list; guest cancel list taken from the first cancelled Booking only
- [ ] Meet set with no minted links suppresses the top location line
- [ ] 'Email is required.' and other BookingService validation messages are untranslated (pre-existing)
- [ ] _bookfields.html lacks {@} parameter declarations; nested ternary in the conflict branch
- [ ] bookSet raises 422 before email/captcha checks and looks the type up twice; scheduleReminders opens an extra read tx for non-set bookings
