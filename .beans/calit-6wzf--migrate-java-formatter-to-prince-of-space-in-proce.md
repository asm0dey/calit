---
# calit-6wzf
title: Migrate Java formatter to Prince of Space (in-process spotless step)
status: completed
type: task
priority: normal
created_at: 2026-09-26T07:42:54Z
updated_at: 2026-09-26T08:33:12Z
---

Spotless 3.9.0+ ships an in-process <princeOfSpace> step, removing the per-file JVM cost that sank the 2026-07 spike.

- [ ] Replace palantirJavaFormat with princeOfSpace in pom.xml
- [ ] Reformat tree, time it
- [x] Full mvn test green (CI; local Docker blocked by pending reboot)
- [x] Update CLAUDE.md formatting section
- [x] Record formatter + module-import decisions in precedent graph

## Summary of Changes

Switched Spotless's Java step from palantir-java-format to Prince of Space 2.2.0 (in-process, 120 cols, level 25), reformatted the tree, moved JDK imports to JEP 511 module imports, and hid the two mechanical commits from blame via .git-blame-ignore-revs (merge with a merge commit). POS double-escapes split string literals; 6 literals were hand-fixed and verified with a compiled-constant diff against main. Upstream bug report drafted in issue_body.md. PR #234.
