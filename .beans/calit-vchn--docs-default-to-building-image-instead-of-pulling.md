---
# calit-vchn
title: Docs default to building image instead of pulling prebuilt
status: completed
type: bug
priority: normal
created_at: 2026-08-01T14:57:12Z
updated_at: 2026-08-01T15:02:15Z
---

quick-start.md and docker-compose.md tell self-hosters to build locally (build: ., docker compose up --build). Should default to pulling ghcr.io/asm0dey/calit prebuilt image. Repo docker-compose.yml stays build-from-source (leave it).

- [x] docker-compose.md: inline compose uses image:, demote build:. to Build-from-source note, drop --build
- [x] quick-start.md: step1 download files not clone+build, step3 up -d pulls prebuilt
- [x] verify docs build / no dangling anchor refs

## Summary of Changes

- Repo docker-compose.yml: app service build:. -> image: ghcr.io/asm0dey/calit:latest (was contradicting README claim of pulling; build-from-source noted in header comment).
- docs docker-compose.md: primary compose image-based; added Build-from-source section; native subsection promoted; startup up -d not --build.
- docs quick-start.md: step1 curl the 2 config files (no clone/build); step3 up -d pulls prebuilt.
- upgrading.md already correct (pull+up recommended, --build under Build-from-source).

Note: docker-compose.yml change is on main working tree; docs changes on branch fix/docs-prebuilt-image (worktree off docs-site). Neither committed yet.
