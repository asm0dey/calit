---
# calit-uf9w
title: Try cribro diff coverage / mutation testing on calit
status: completed
type: task
priority: normal
created_at: 2026-11-09T13:54:51Z
updated_at: 2026-11-09T13:59:55Z
---

Install cribro (https://github.com/cribro-dev/cribro) user-level via -Dmaven.ext.class.path, no project build change (only a SNAPSHOT exists). Check it works on calit.

## Summary of Changes

Installed cribro user-level from source (a4e546e): ~/.m2/cribro/cribro-maven.jar, plugin in ~/.m2/repository, skill in ~/.claude/skills/cribro. No calit build file changed: only 0.1.0-SNAPSHOT exists, so .mvn/extensions.xml would break CI. Verified diff coverage and cribro:pitest-diff on commit 87f53fa0 with plain JUnit and @QuarkusTest classes.
