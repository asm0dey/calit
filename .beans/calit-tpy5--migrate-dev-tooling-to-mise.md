---
# calit-tpy5
title: Migrate dev tooling to mise
status: completed
type: task
priority: normal
created_at: 2026-09-29T20:46:33Z
updated_at: 2026-09-29T21:06:06Z
---

Pin JDK, Bun, beans, actionlint in mise.toml; CI uses jdx/mise-action instead of setup-java/setup-bun.

- [x] mise.toml
- [x] CI workflows use mise-action
- [x] CLAUDE.md / CONTRIBUTING setup steps
- [x] verify locally + CI

## Summary of Changes

mise.toml pins Liberica JDK 26.0.2.1, Bun 1.4.2, beans 0.4.2, actionlint 1.7.12 plus thin tasks. ci/fork-ci/codeql use jdx/mise-action + ~/.m2 cache; sonar-fork keeps setup-java (fork mise.toml next to SONAR_TOKEN). PR #239, CI green.
