---
# calit-blpt
title: Message parity test doesn't check placeholder agreement across locales
status: todo
type: task
priority: low
created_at: 2026-09-09T22:55:23Z
updated_at: 2026-09-09T22:55:23Z
---

MultiHostMessageParityTest asserts key presence and absence of orphans, but not that each locale's value uses the same {placeholder} tokens as the @Message default. CLAUDE.md calls placeholder parity a rule, so a typo'd or dropped placeholder in a de/he value would ship silently and render the literal token to a user.

Fix: extract the {...} token set per key from the @Message default and assert set equality against each locale's value.

Surfaced by the final review of GH #198 / bean calit-ot22.
