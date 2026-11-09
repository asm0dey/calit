---
# calit-cbvq
title: 'Renovate group PR stuck: Quarkus regex rejects four-part versions'
status: completed
type: bug
priority: normal
created_at: 2026-11-09T14:02:53Z
updated_at: 2026-11-09T14:21:28Z
---

PR 257 frozen since 10-18 with byte-buddy -jdk5: dashboard shows 'Error updating branch: update failure'. README/CLAUDE.md custom manager only matches X.Y.Z, Quarkus update is 3.40.1.1.

## Summary of Changes

Widened the Quarkus custom-manager regexes to three or four version parts (#262). Renovate then updated #257 to byte-buddy 1.18.17 and Quarkus 3.40.1.1; it merged.
