---
# calit-caxr
title: Run SonarQube analysis on fork PRs via workflow_run
status: in-progress
type: task
priority: normal
created_at: 2026-09-08T12:41:15Z
updated_at: 2026-09-08T12:44:19Z
---

Sonar is skipped on fork PRs (ci.yml:60 guards on head.repo.fork) because pull_request runs from forks get no SONAR_TOKEN. PR #184 (CommanderTvis) got no quality gate.

Fix: the unprivileged CI job uploads compiled output as an artifact; a workflow_run job in the base repo context runs the analysis with secrets.

Security constraint: the privileged job must not execute fork-authored build config. It checks out the PR head for sources and history, then restores pom.xml/.mvn/mvnw from the base branch, so Maven runs OUR build config against THEIR sources. Compiled classes and the jacoco report arrive as data, never executed.

- [x] ci.yml: upload sonar inputs on fork PRs
- [x] sonar-fork.yml: workflow_run job
- [x] PR number resolved from the API by head SHA, never from the artifact
- [ ] open PR
