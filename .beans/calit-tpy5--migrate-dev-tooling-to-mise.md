---
# calit-tpy5
title: Migrate dev tooling to mise
status: in-progress
type: task
priority: normal
created_at: 2026-09-29T20:46:33Z
updated_at: 2026-09-29T20:56:51Z
---

Pin JDK, Bun, beans, actionlint in mise.toml; CI uses jdx/mise-action instead of setup-java/setup-bun.

- [x] mise.toml
- [x] CI workflows use mise-action
- [x] CLAUDE.md / CONTRIBUTING setup steps
- [ ] verify locally + CI (local: 1271 tests green via mise run test; CI pending)
