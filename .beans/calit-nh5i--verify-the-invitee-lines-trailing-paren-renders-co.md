---
# calit-nh5i
title: 'Verify the Invitee: line''s trailing paren renders correctly in the Hebrew email copy'
status: todo
type: bug
priority: low
created_at: 2026-09-09T22:16:28Z
updated_at: 2026-09-09T22:16:28Z
---

`templates/email/_invitee.html` renders `{inviteeName} (<a href="mailto:...">{inviteeEmail}</a>)`. The #196 plan asserted the parentheses read correctly in both LTR and RTL. The final reviewer disputes that: under `dir="rtl"` (`email/layout.html` sets it for `lang == 'he'`) the closing `)` is a neutral character between an LTR run and end-of-line, so the bidi algorithm resolves it to the paragraph level and mirrors it. Name and address are unaffected — it is one glyph.

This is a rendering claim reasoned about, not observed. Verify by eye in a real Hebrew mail client before changing anything.

If it is real: wrap the value in `<span dir="ltr">…</span>` (good mail-client support; `<bdi>` support is patchier).

Cosmetic, `he` locale only. Raised by the final whole-branch review of #196.
