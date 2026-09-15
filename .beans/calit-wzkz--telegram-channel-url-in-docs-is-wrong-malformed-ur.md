---
# calit-wzkz
title: Telegram channel URL in docs is wrong; malformed URLs save but never deliver
status: in-progress
type: bug
priority: high
created_at: 2026-09-15T11:01:27Z
updated_at: 2026-09-15T11:08:23Z
---

GitHub issue #216.

Docs give `telegram://<bot-token>/<chat-id>`. notify4j's parser wants
`telegram://api.telegram.org/<bot-token>/<chat-id>` — the authority is the Bot API host,
not a secret. The documented form saves fine and then throws at delivery.

Two defects:
1. Wrong URL shape in docs + test fixtures.
2. `ChannelPolicy.check` admits it: `catalog.tryParse` only DECOMPOSES, it never checks
   required fields, so an incomplete URL passes save-time policy and only fails at send.

- [x] Reproduce standalone against notify4j-core 1.1.1
- [x] Reject incomplete URLs at save time (required-field validation)
- [x] Fix fixtures to the real telegram URL shape
- [x] Fix hostBearing: telegram/ntfy/gotify authorities ARE hosts
- [x] i18n de/he for the new message
- [x] mvn test green (1145 tests, 0 failures)
- [ ] docs-site: notification-channels page + changelog

## Cross-scheme audit

Ran the required-field guard against notify4j's DELIVERY parser (`NotifierUrlParser.parse`, no HTTP)
over all 21 standard schemes: each canonical URL plus every path truncation, 49 URLs. **0 mismatches** —
the guard refuses exactly what delivery refuses. So the guard is not a telegram patch.

Two URL shapes were literally spelled out in calit's docs, and BOTH were wrong:
- `telegram://<bot-token>/<chat-id>` -> needs `telegram://api.telegram.org/<bot-token>/<chat-id>`
- `ntfy://<topic>` -> needs `ntfy://<host>/<topic>`

Every other scheme deferred to notify4j's own reference and its canonical shape round-trips clean.
