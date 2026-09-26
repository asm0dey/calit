---
# calit-6wzf
title: Migrate Java formatter to Prince of Space (in-process spotless step)
status: in-progress
type: task
priority: normal
created_at: 2026-09-26T07:42:54Z
updated_at: 2026-09-26T07:58:54Z
---

Spotless 3.9.0+ ships an in-process <princeOfSpace> step, removing the per-file JVM cost that sank the 2026-07 spike.

- [ ] Replace palantirJavaFormat with princeOfSpace in pom.xml
- [ ] Reformat tree, time it
- [ ] Full mvn test green
- [x] Update CLAUDE.md formatting section
- [x] Record formatter + module-import decisions in precedent graph
