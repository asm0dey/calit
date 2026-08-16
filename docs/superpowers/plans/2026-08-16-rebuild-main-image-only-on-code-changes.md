# Rebuild main image only on code changes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Bean:** `calit-1aqn` — Rebuild main image only on code changes

**Goal:** Stop rebuilding and pushing the `edge` container images on pushes to `main` that touched only non-code files (beans, markdown, agent config, LICENSE).

**Architecture:** Add one cheap `changes` job to `.github/workflows/ci.yml` that diffs the pushed commit range with `git diff --name-only <before> <sha>` under exclude-pathspecs, and exposes a single boolean output `code`. The existing `build` matrix job gains `needs: [test, changes]` and an extra `needs.changes.outputs.code == 'true'` clause in its `if:`. `merge` needs `build`, so it skips transitively — nothing else changes. The filter **fails open**: tag pushes, an unknown/zero `before` SHA (first push, force-push to a rewritten history, unreachable object) all yield `code=true`, so an ambiguous push still publishes.

**Tech Stack:** GitHub Actions (`on.push` for `main` + `v*` tags), `git diff` exclude-pathspec magic, no new actions/dependencies.

## Global Constraints

- **No new GitHub Action dependency.** `dorny/paths-filter` is not to be added; the repo manages actions through Renovate and a 10-line `git diff` covers this. (`ponytail:` rung 2 — the tool already installed does it.)
- **A `v*` tag push MUST always build and publish**, unconditionally, regardless of what the diff says. A skipped release is the only unacceptable failure mode here.
- **Fail open, never fail closed.** Any case the script cannot classify with certainty ⇒ `code=true` ⇒ build runs.
- The `test` and `scan` jobs are **not** gated — they still run on every push and PR (required checks; skipping them would change PR merge semantics).
- Do not add `paths`/`paths-ignore` to the workflow-level `on:` block — it applies to tag pushes too and would put constraint #2 at risk.
- Non-code paths (exact list, used verbatim in the pathspec): `.beans/**`, `*.md` (any depth), `.agents/**`, `.claude/**`, `LICENSE`.
- Everything else is code — notably `pom.xml`, `src/**`, `Dockerfile`, `Dockerfile.native`, `package.json`, `bun.lock`, `src/main/css/**`, `.github/**`.
- YAML style: 2-space indent, matches surrounding file. Shell steps start with `set -euo pipefail`.

---

## File Structure

- `.github/workflows/ci.yml` — **modify only**. Two edits: (a) new `changes` job inserted between `scan` and `build`; (b) `build` job's `needs:` and `if:` extended. No other job touched.
- `CLAUDE.md` — **modify**, one sentence in the "Docker / CI" section recording the new gate so future contributors don't think CI is broken when no image appears.
- `.beans/calit-1aqn--rebuild-main-image-only-on-code-changes.md` — **modify**, tick todos + `## Summary of Changes`.

No new files. There is no test framework for GitHub Actions here; verification is (1) running the exact `git diff` command locally against two real commits with known-different content, and (2) observing the first real pushes after merge.

---

### Task 1: Add the `changes` job and gate `build` on it

**Files:**
- Modify: `.github/workflows/ci.yml:61-82` (insert new job after the `scan` job that ends at line 75; extend the `build` job header at lines 77-82)
- Test: none — verified by running the diff command against real commits (Steps 1-3) and by CI observation (Task 3)

**Interfaces:**
- Produces: job id `changes` with output `code`, a string that is exactly `"true"` or `"false"`. Consumed by `build` as `needs.changes.outputs.code`. Anything comparing against it must use the string `'true'`, not a boolean.

- [ ] **Step 1: Verify the exclude-pathspec is empty for a non-code commit**

The command that the job will run, checked here against `7e9c706` ("chore(beans): track GH #118 …") — a commit that touched exactly one file under `.beans/`.

```bash
cd /home/finkel/work_self/calit
git diff --name-only 7e9c706^ 7e9c706 -- . \
  ':(exclude).beans/**' ':(exclude)*.md' ':(exclude).agents/**' \
  ':(exclude).claude/**' ':(exclude)LICENSE'
```

Expected: **no output at all** (zero lines). If any line prints, the pathspec list is wrong — stop and fix it before continuing.

- [ ] **Step 2: Verify the same pathspec is non-empty for a code commit**

Checked against `41516f7` ("fix(i18n): viewer-local time format …"), which touched `src/**`.

```bash
cd /home/finkel/work_self/calit
git diff --name-only 41516f7^ 41516f7 -- . \
  ':(exclude).beans/**' ':(exclude)*.md' ':(exclude).agents/**' \
  ':(exclude).claude/**' ':(exclude)LICENSE' | head -3
```

Expected: at least three lines, beginning with

```
src/main/java/site/asm0dey/calit/domain/OwnerSettings.java
src/main/java/site/asm0dey/calit/email/EmailService.java
src/main/java/site/asm0dey/calit/i18n/AdminMessages.java
```

- [ ] **Step 3: Verify a `*.md` at depth is excluded (glob sanity check)**

`git` pathspec wildcards match across `/` by default; this confirms it rather than assuming it.

```bash
cd /home/finkel/work_self/calit
git ls-files -- . ':(exclude)*.md' | grep -c '\.md$'
```

Expected: `0`.

- [ ] **Step 4: Insert the `changes` job**

In `.github/workflows/ci.yml`, immediately after the `scan` job's last line (`          ignore-unfixed: true`, line 75) and before `  build:`, insert:

```yaml
  # Docs/bean-only pushes to main produce a byte-identical image; skip the whole
  # image pipeline for them. Fails OPEN — anything we cannot diff with certainty
  # (tag push, unknown/zero `before` SHA, force-push over rewritten history)
  # reports code=true so a release is never silently skipped.
  changes:
    name: Detect code changes
    runs-on: ubuntu-latest
    if: github.event_name == 'push'
    outputs:
      code: ${{ steps.filter.outputs.code }}
    steps:
      - name: Check out the repo
        uses: actions/checkout@v7
        with:
          fetch-depth: 0 # need the pushed commit range, not just its tip

      - name: Classify changed paths
        id: filter
        env:
          BEFORE: ${{ github.event.before }}
        run: |
          set -euo pipefail
          if [ "${GITHUB_REF}" != "refs/heads/main" ]; then
            echo "not a main push (${GITHUB_REF}) -> code=true"
            echo "code=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          if [ -z "${BEFORE:-}" ] || [ "$BEFORE" = "0000000000000000000000000000000000000000" ] \
             || ! git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
            echo "no usable before SHA ('${BEFORE:-}') -> code=true"
            echo "code=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          changed=$(git diff --name-only "$BEFORE" "$GITHUB_SHA" -- . \
            ':(exclude).beans/**' ':(exclude)*.md' ':(exclude).agents/**' \
            ':(exclude).claude/**' ':(exclude)LICENSE')
          if [ -n "$changed" ]; then
            echo "code changed:"; echo "$changed"
            echo "code=true" >> "$GITHUB_OUTPUT"
          else
            echo "only non-code paths changed -> skipping image build"
            git diff --name-only "$BEFORE" "$GITHUB_SHA"
            echo "code=false" >> "$GITHUB_OUTPUT"
          fi
```

- [ ] **Step 5: Gate the `build` job**

In the same file, replace the `build` job's `needs:`/`if:` header (currently lines 79-82):

```yaml
    needs: [test]
    if: >-
      github.event_name == 'push' &&
      (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v'))
```

with:

```yaml
    needs: [test, changes]
    if: >-
      github.event_name == 'push' &&
      (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')) &&
      needs.changes.outputs.code == 'true'
```

Leave every other line of `build` — matrix, steps, permissions — untouched. `merge` already has `needs: build`, so it skips transitively; do not edit it.

- [ ] **Step 6: Validate the YAML parses and the job graph is intact**

```bash
cd /home/finkel/work_self/calit
python3 -c "
import yaml
w = yaml.safe_load(open('.github/workflows/ci.yml'))
jobs = w['jobs']
print('jobs:', list(jobs))
print('build needs:', jobs['build']['needs'])
print('changes outputs:', jobs['changes']['outputs'])
assert jobs['build']['needs'] == ['test', 'changes']
assert \"needs.changes.outputs.code == 'true'\" in jobs['build']['if']
assert jobs['merge']['needs'] == 'build'
print('OK')
"
```

Expected last line: `OK`, with `jobs: ['test', 'scan', 'changes', 'build', 'merge', 'release']`.

- [ ] **Step 7: Lint the embedded shell (catches quoting mistakes the YAML parse cannot)**

```bash
cd /home/finkel/work_self/calit
command -v actionlint >/dev/null && actionlint .github/workflows/ci.yml || echo "actionlint not installed - skipping (optional)"
```

Expected: no output from actionlint (clean), or the "not installed" line. If actionlint reports errors in the new job, fix them; pre-existing warnings in other jobs are out of scope.

- [ ] **Step 8: Commit**

```bash
cd /home/finkel/work_self/calit
git add .github/workflows/ci.yml
git commit -m "ci: skip image rebuild on docs/bean-only pushes to main

Adds a `changes` job that diffs the pushed range under exclude-pathspecs
and gates the image matrix on it. Fails open: tag pushes and any push
with an unusable before-SHA still build."
```

---

### Task 2: Record the gate in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`, "## Docker / CI" section

**Interfaces:**
- Consumes: the `changes` job id and its non-code path list from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Read the current section**

```bash
cd /home/finkel/work_self/calit
grep -n -A 6 '^## Docker / CI' CLAUDE.md
```

- [ ] **Step 2: Append the note**

Add this sentence to the end of the "Docker / CI" paragraph (after the Renovate sentence), preserving the section's existing prose style:

```markdown
The `changes` job gates the image matrix: a push to `main` that touched only `.beans/**`, `**/*.md`, `.agents/**`, `.claude/**` or `LICENSE` builds no image and publishes no `edge`/`sha-*` tag — that is intentional, not a broken run. `v*` tag pushes always build.
```

- [ ] **Step 3: Verify the note landed and nothing else moved**

```bash
cd /home/finkel/work_self/calit
git diff --stat CLAUDE.md
```

Expected: `1 file changed, 1 insertion(+)` (or `1 insertion(+), 1 deletion(-)` if the sentence merged into an existing paragraph line).

- [ ] **Step 4: Commit**

```bash
cd /home/finkel/work_self/calit
git add CLAUDE.md
git commit -m "docs: note the CI image-build path gate in CLAUDE.md"
```

**Not doing:** no `docs-site` branch entry. The `docs-site` rule in CLAUDE.md covers user-facing changes (env vars, routes, config flags, setup steps, features); CI internals are not user-facing and no published doc page describes the image pipeline's triggers.

---

### Task 3: Verify on real pushes and close the bean

**Files:**
- Modify: `.beans/calit-1aqn--rebuild-main-image-only-on-code-changes.md` (via the `beans` CLI, not by hand)

**Interfaces:**
- Consumes: merged Tasks 1-2 on `main`.

- [ ] **Step 1: Open the PR**

Per CLAUDE.md/memory, `main` is never pushed to directly.

```bash
cd /home/finkel/work_self/calit
git switch -c ci/skip-image-build-on-docs-only
git push -u origin ci/skip-image-build-on-docs-only
gh pr create --fill --base main
```

- [ ] **Step 2: Confirm the PR run is unaffected**

```bash
cd /home/finkel/work_self/calit
gh pr checks --watch
```

Expected: `test` and `scan` run and pass. `changes` is skipped (its `if:` requires `github.event_name == 'push'`). `build`/`merge` are skipped, exactly as before this change — a PR never built images.

- [ ] **Step 3: Merge, then confirm the code-change push DOES build**

The merge commit itself contains `.github/workflows/ci.yml` (a code path), so this push must build.

```bash
cd /home/finkel/work_self/calit
gh pr merge --squash --delete-branch
sleep 30
gh run list --branch main --limit 1
gh run view --log-failed 2>/dev/null | head -20 || true
```

Expected: the newest `main` run has `build`/`merge` jobs in `in_progress`/`completed`, not `skipped`. Inspect the `changes` job log: it prints `code changed:` followed by `.github/workflows/ci.yml`.

- [ ] **Step 4: Confirm a non-code push does NOT build**

The bean closeout in Step 5 is itself a `.beans/**`-only commit — use it as the live test.

- [ ] **Step 5: Close the bean (this commit is the test fixture for Step 4)**

```bash
cd /home/finkel/work_self/calit
beans query 'mutation {
  updateBean(id: "calit-1aqn", input: {
    status: "completed"
    bodyMod: {
      replace: [
        { old: "- [ ] Decide gating mechanism", new: "- [x] Decide gating mechanism" }
        { old: "- [ ] Implement in", new: "- [x] Implement in" }
        { old: "- [ ] Verify:", new: "- [x] Verify:" }
      ]
      append: "## Summary of Changes\n\nAdded a `changes` job to `.github/workflows/ci.yml` that diffs the pushed commit range with `git diff --name-only <before> <sha>` under exclude-pathspecs (`.beans/**`, `*.md`, `.agents/**`, `.claude/**`, `LICENSE`) and exposes a `code` output. The `build` matrix job now requires `needs.changes.outputs.code == 'true'`; `merge` skips transitively. No new action dependency. Fails open: tag pushes and any push with an unusable `before` SHA report `code=true`, so a `v*` release always publishes. Noted in CLAUDE.md's Docker / CI section."
    }
  }) { id status }
}'
```

- [ ] **Step 6: Push the bean-only commit on a branch and merge it**

```bash
cd /home/finkel/work_self/calit
git switch -c chore/close-calit-1aqn
git add .beans/calit-1aqn--rebuild-main-image-only-on-code-changes.md
git commit -m "chore(beans): complete calit-1aqn — CI builds images only on code changes"
git push -u origin chore/close-calit-1aqn
gh pr create --fill --base main
gh pr merge --squash --delete-branch --auto
```

- [ ] **Step 7: Confirm the image pipeline skipped**

```bash
cd /home/finkel/work_self/calit
sleep 60
gh run list --branch main --limit 1 --json databaseId,conclusion --jq '.[0]'
gh run view "$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')" \
  --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
```

Expected: `Detect code changes: success`, `Build & test (Maven): success`, and **every** `Build … image (…)` and `Merge multi-arch manifest (…)` job reported as `skipped`. The `changes` job log ends with `only non-code paths changed -> skipping image build`.

If the image jobs ran anyway, read the `changes` job log — it prints the full unfiltered file list on the `code=false` path and the filtered list on the `code=true` path, which shows exactly which path defeated the pathspec.

---

## Known limitation (accepted, not a bug)

A skipped push produces no `sha-<short>` image tag for that commit. Anyone pinning by commit SHA must pin a commit that actually changed code. Documented in CLAUDE.md as of Task 2.
