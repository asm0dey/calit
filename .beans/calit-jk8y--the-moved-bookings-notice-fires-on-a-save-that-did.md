---
# calit-jk8y
title: The moved-bookings notice fires on a save that did not change the calendar
status: todo
type: bug
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

After saving a meeting type's write calendar, the page reports how many upcoming bookings stay on the calendar they were created on. It counts bookings whose stored calendar differs from the new target — so it also fires when the save did not change the target at all, if older bookings already sit elsewhere.

The statement is never false; it is just surprising, implying a move happened when nothing moved.

Found in the final whole-branch review of [[calit-bh5t]].

- [ ] Compare the pre-save ref against the post-save one and only show the notice when the target actually changed
