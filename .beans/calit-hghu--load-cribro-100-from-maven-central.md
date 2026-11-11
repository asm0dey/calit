---
# calit-hghu
title: Load cribro 1.0.0 from Maven Central
status: completed
type: task
priority: normal
created_at: 2026-11-11T15:18:52Z
updated_at: 2026-11-11T15:22:07Z
---

cribro 1.0.0 is on Central. Load it via .mvn/extensions.xml instead of building a pinned commit in ci.yml/fork-ci.yml.

- [x] .mvn/extensions.xml
- [x] ci.yml + fork-ci.yml drop the from-source build
- [x] Full suite green with the extension
- [x] PR

## Summary of Changes

.mvn/extensions.xml loads cribro-maven-plugin 1.0.0 from Central; ci.yml and fork-ci.yml drop the pinned from-source build and pass -Dcribro.diffBase on PRs. Full suite green with the extension (1500 tests).
