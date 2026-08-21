---
# calit-xcp9
title: 'Clear SonarCloud code smells on PR #142 (write override)'
status: completed
type: task
priority: normal
created_at: 2026-08-21T19:09:03Z
updated_at: 2026-08-21T19:16:04Z
---

Fix S3358 nested ternary duplication (AdminResource.java:718, SharedMeetingsResource.java:246) by extracting shared WriteTargetResolver.writeCalendarValue() method; fix S3776 cognitive complexity in SharedMeetingsResource.saveBuffers (line 368) and AdminResource (line 789) by extracting write-calendar handling into private methods. No behaviour change.



## Progress
- [x] Extracted WriteTargetResolver.writeCalendarValue(override, dangling) - fixes S3358 dup at AdminResource.java:718 and SharedMeetingsResource.java:246
- [x] Extracted AdminResource.applyWriteCalendar(t, writeCalendar) - fixes S3776 at AdminResource.editMeetingType:789
- [x] Extracted SharedMeetingsResource.applyWriteCalendar(h, typeId, keep, ref) - fixes S3776 at saveBuffers:368
- [x] spotless:apply / spotless:check clean, no CleanThat mangling
- [x] Targeted tests green (AdminWriteCalendarTest, SharedWriteCalendarTest, WriteTargetResolverTest)
- [ ] Full suite running in background, awaiting result



## Summary of Changes
Full suite: 885 tests, 0 failures, 0 errors (verified independently against coordinator's aggregation). Report written to .superpowers/sdd/2026-08-17-per-meeting-type-write-target/sonar-cleanup-report.md. Traced both !keep/absent-field guard rewrites via De Morgan's law by hand - no behavior change, confirmed by unedited AdminWriteCalendarTest/SharedWriteCalendarTest passing plus manual diff trace.
