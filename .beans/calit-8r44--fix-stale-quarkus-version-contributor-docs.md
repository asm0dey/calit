---
# calit-8r44
title: Fix stale Quarkus version + contributor docs
status: completed
type: task
priority: normal
created_at: 2026-09-26T14:26:10Z
updated_at: 2026-09-26T14:27:15Z
---

README badge + CLAUDE.md say Quarkus 3.38 (pom: 3.39.5); CONTRIBUTING formatter line says palantir.

- [x] Renovate regex manager keeps Quarkus version in README/CLAUDE.md in sync
- [x] CONTRIBUTING: formatter, module imports, red-suite rule, changelog Unreleased, mvnw consistency
- [x] CLAUDE.md: Docker Hub publishing, stale bits

## Summary of Changes

renovate.json gains a regex customManager (maven quarkus-bom) for the README badge and CLAUDE.md line 7, now 3.39.5. CONTRIBUTING: Prince of Space formatter, module imports, red-suite rule, changelog-at-merge, release promotion, origin/main branching. CLAUDE.md: Docker Hub + native images, distroless runtime, Renovate note. Dockerfile build-stage comment JDK 25->26.
