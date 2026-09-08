---
# calit-caxr
title: Run SonarQube analysis on fork PRs via workflow_run
status: completed
type: task
priority: normal
created_at: 2026-09-08T12:41:15Z
updated_at: 2026-09-08T13:02:59Z
---

Sonar is skipped on fork PRs (ci.yml:60 guards on head.repo.fork) because pull_request runs from forks get no SONAR_TOKEN. PR #184 (CommanderTvis) got no quality gate.

Fix: the unprivileged CI job uploads compiled output as an artifact; a workflow_run job in the base repo context runs the analysis with secrets.

Security constraint: the privileged job must not execute fork-authored build config. It checks out the PR head for sources and history, then restores pom.xml/.mvn/mvnw from the base branch, so Maven runs OUR build config against THEIR sources. Compiled classes and the jacoco report arrive as data, never executed.

- [x] ci.yml: upload sonar inputs on fork PRs
- [x] sonar-fork.yml: workflow_run job
- [x] PR number resolved from the API by head SHA, never from the artifact
- [x] open PR

## Summary of Changes

PR #186 (branch `ci/sonar-fork-prs`).

- `ci.yml`: on a fork PR the test job tars `target/{classes,test-classes,jacoco-report,surefire-reports}` and uploads it as `sonar-inputs` (1-day retention). `target/classes` is mandatory; the report dirs are best-effort. Same-repo PRs are untouched and keep analysing inline.
- `.github/workflows/sonar-fork.yml`: `workflow_run` on CI completion, filtered to successful fork `pull_request` runs. Resolves the PR from the API by head SHA, checks out `refs/pull/N/head` at full depth for blame, restores `pom.xml`/`mvnw`/`.mvn` from the base branch, unpacks the artifact behind a member-prefix guard, runs `sonar-maven-plugin:sonar` with explicit `sonar.pullrequest.*` coordinates.
- Privileged job holds `SONAR_TOKEN` with `GITHUB_TOKEN` scoped `contents: read` + `pull-requests: read`.

Known consequence: a PR editing `pom.xml` is analysed against the BASE pom. Deliberate (a fork cannot widen `sonar.issue.ignore` to hide its findings), but a PR adding a new source root needs the base pom updated first.

End-to-end verification needs a real fork PR; the guard logic and staging script were exercised locally (legit archive passes, smuggled `pom.xml` and `../../` traversal both rejected).

## Rework after reading SonarSource's guidance

The first pass was designed from first principles and missed the vendor's documented pattern for fork PRs. Two corrections:

- **Command injection (real bug).** The fork's branch name was interpolated into a `run:` line in the job holding SONAR_TOKEN. Git permits `'`, `;`, backtick and `$(` in ref names, so a crafted branch escaped the quoting. Now every value is regex-checked and passed via `env:`.
- **Three-workflow split.** The fork build moved to its own `fork-ci.yml`; `workflow_run` keys on it, and `ci.yml`'s test job now skips fork PRs. Also adopted: `persist-credentials: false`, explicit `sonar.pullrequest.provider`/`github.repository`, artifact deletion after the scan.

Deliberate deviation: their example runs `sonarqube-scan-action`; we run Maven with the base-branch pom. The standalone scanner cannot resolve `sonar.java.libraries`, so fork PRs would get a weaker analysis than ours. Documented in the workflow header.
