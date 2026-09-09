---
# calit-zhib
title: 'mailto: href in owner emails is HTML-escaped but not URL-escaped'
status: todo
type: bug
priority: low
created_at: 2026-09-09T22:16:10Z
updated_at: 2026-09-09T22:16:10Z
---

`templates/email/_invitee.html` renders `<a href="mailto:{inviteeEmail}">`. Qute HTML-escapes the value, so no attribute breakout — but it does not URL-escape it. `?` and `&` are legal RFC 5322 atext in a local part and `BookingService.isPlausibleEmail` only rejects whitespace and commas, so an address like `foo?subject=X&body=Y@attacker.example` renders a link a mail client reads as recipient `foo` with attacker-chosen subject and body.

Impact is low: the owner must click AND send, and the unescaped plain-text address sits right beside the link where the oddity is visible. Pre-existing property of the address validator surfacing in a new place — not introduced by #196.

Two cheap fixes, pick one:
- A Qute `@TemplateExtension` that percent-encodes `?`, `&` and `#` for the href only.
- Extend `isPlausibleEmail` to reject `?` and `&` in the local part (loses a sliver of legitimate addresses).

Found by the final whole-branch review of #196.
