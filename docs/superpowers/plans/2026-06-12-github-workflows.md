# GitHub Actions CI/CD Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single GitHub Actions workflow that builds + tests the calit Quarkus app, then (test-gated) publishes a public multi-arch Docker image to GHCR and cuts a GitHub Release on version tags.

**Architecture:** One `.github/workflows/ci.yml` with five jobs: `test` (Maven `verify` with Quarkus Dev Services Postgres), `css` (Bun Tailwind/daisyUI build check), `build` (native amd64+arm64 matrix → push-by-digest), `merge` (combine digests into one multi-arch manifest with all tags), `release` (GitHub Release on `v*` tags). `build`/`merge`/`release` only run on `push` and require `test`+`css` to pass, so nothing untested is ever published. Multi-arch uses **native runners** (`ubuntu-latest` + `ubuntu-24.04-arm`) — no QEMU emulation. The existing multistage `Dockerfile` is reused unchanged in structure but gains BuildKit cache mounts; `reproducible-containers/buildkit-cache-dance` persists `.m2`/Bun caches across ephemeral runners. Dependency updates are handled externally by Mend Renovate (no Dependabot in this repo).

**Tech Stack:** GitHub Actions; `actions/checkout@v6`, `actions/setup-java@v4` (Liberica 26, `cache: maven`), `oven-sh/setup-bun@v2`, `docker/setup-buildx-action@v3`, `docker/login-action@v3`, `docker/metadata-action@v5`, `docker/build-push-action@v6`, `actions/upload-artifact@v4`, `actions/download-artifact@v4`, `reproducible-containers/buildkit-cache-dance@v3`, `actions/cache@v4`, `softprops/action-gh-release@v2`; Maven wrapper, Bun, Quarkus 3.36, Java `release=25`.

---

## Context the implementer MUST know

- **Repo:** `asm0dey/calit` (remote `git@github.com:asm0dey/calit.git`). GHCR image: `ghcr.io/asm0dey/calit`. Default branch `main`.
- **No `.github/` exists yet.** This plan creates it.
- **Java:** `pom.xml` has `<maven.compiler.release>25</maven.compiler.release>`; `Dockerfile` uses Liberica JDK 26. CI uses `distribution: liberica`, `java-version: '26'` everywhere (decision: always Liberica 26 — Java 26 is GA, `setup-java` resolves it).
- **Tests:** `./mvnw verify`. Tests use Quarkus **Dev Services** → Testcontainers Postgres, pulled anonymously; the Docker daemon is preinstalled on GitHub-hosted Linux runners. `<reuseForks>true</reuseForks>`, `quarkus.http.test-port=0`. All secrets have safe defaults; `%test` sets mocks (mailer mocked, scheduler off, Google OAuth + Turnstile use documented test keys). **CI needs zero repo secrets** for tests to pass.
- **CSS:** `package.json` script `css:build` = `tailwindcss -i src/main/css/input.css -o src/main/resources/META-INF/resources/calit.css --minify`. Built with Bun (`bun.lock` present). Tests do NOT need compiled CSS — the `css` job is an independent early-fail guard against Tailwind/template breakage.
- **Docker:** multistage `Dockerfile` (Bun CSS → Liberica Maven build with `-DskipTests` → `jre-26-musl` runtime). It is the single source for image builds; the `build` job uses it for both arches.
- **Fork PRs:** `build`/`merge`/`release` are gated to `push` events only, so the read-only `GITHUB_TOKEN` on fork PRs never blocks them — fork PRs run only `test`+`css`.
- **Action version pins:** versions above were current as of 2026-06. If `setup-java` cannot resolve Liberica 26 at execution time, that is a blocker to raise — do NOT silently downgrade below release 25.

## File Structure

- Modify: `Dockerfile` — add `RUN --mount=type=cache` mounts (Maven `.m2`, Bun cache).
- Create: `.github/workflows/ci.yml` — the entire pipeline (5 jobs), built up across Tasks 3–7.
- Modify: `.gitignore` — ignore the local `actionlint` binary.

The final assembled `ci.yml` is shown in full in Task 7, Step 1, so it can be cross-checked against the incremental tasks.

---

## Task 1: Install local validation tool (actionlint)

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Install actionlint**

Run:

```bash
cd /home/finkel/work_self/calit
bash <(curl -fsSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
./actionlint -version
```

Expected: prints a version (e.g. `1.7.x`); binary `actionlint` dropped in repo root.

- [ ] **Step 2: Ignore the binary**

Run:

```bash
cd /home/finkel/work_self/calit
grep -qxF '/actionlint' .gitignore || printf '\n# local CI validator\n/actionlint\n' >> .gitignore
```

Expected: no error; `/actionlint` present in `.gitignore`.

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore: ignore local actionlint binary"
```

---

## Task 2: Add BuildKit cache mounts to the Dockerfile

**Files:**
- Modify: `Dockerfile` (lines 7, 21, 29)

The `# syntax=docker/dockerfile:1` directive on line 1 already enables `RUN --mount`. We add cache mounts so repeated builds reuse the Bun download cache and Maven `.m2`. The cache-mount targets (`/root/.bun/install/cache`, `/root/.m2`) are referenced by `buildkit-cache-dance` in Task 5.

- [ ] **Step 1: Cache-mount the Bun install**

In `Dockerfile`, replace line 7:

```dockerfile
RUN bun install --frozen-lockfile
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.bun/install/cache \
    bun install --frozen-lockfile
```

- [ ] **Step 2: Cache-mount the Maven dependency warm-up**

Replace line 21:

```dockerfile
RUN ./mvnw -B -q -DskipTests dependency:go-offline
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests dependency:go-offline
```

- [ ] **Step 3: Cache-mount the Maven package build**

Replace line 29:

```dockerfile
RUN ./mvnw -B -q -DskipTests clean package
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests clean package
```

- [ ] **Step 4: Verify the image still builds**

Run (requires local Docker):

```bash
cd /home/finkel/work_self/calit
DOCKER_BUILDKIT=1 docker build -t calit:cache-check .
```

Expected: build succeeds through all stages. (If Docker is unavailable locally, skip and note in handoff — Task 8 covers it in CI.)

- [ ] **Step 5: Commit**

```bash
git add Dockerfile
git commit -m "build: add BuildKit cache mounts for Maven and Bun"
```

---

## Task 3: Create ci.yml — triggers, concurrency, and the `test` job

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow skeleton + `test` job**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: ["main"]
    tags: ["v*"]
  pull_request: {}

# Cancel superseded PR runs; never cancel an in-flight main/tag publish.
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

permissions:
  contents: read

jobs:
  test:
    name: Build & test (Maven)
    runs-on: ubuntu-latest
    steps:
      - name: Check out the repo
        uses: actions/checkout@v6

      - name: Set up Liberica JDK 26
        uses: actions/setup-java@v4
        with:
          distribution: liberica
          java-version: "26"
          cache: maven

      - name: Build and run tests
        run: ./mvnw --batch-mode --no-transfer-progress verify
```

- [ ] **Step 2: Lint**

```bash
cd /home/finkel/work_self/calit
./actionlint .github/workflows/ci.yml
```

Expected: no output (exit 0). A `liberica` distribution note from actionlint is harmless — `setup-java` supports it.

- [ ] **Step 3: Sanity-check the wrapper locally**

```bash
cd /home/finkel/work_self/calit
./mvnw --batch-mode --no-transfer-progress -version
```

Expected: prints Maven + JDK (≥ 25).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add Maven build and test job"
```

---

## Task 4: Add the `css` job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Append the `css` job**

Add under `jobs:` in `.github/workflows/ci.yml` (sibling of `test`):

```yaml
  css:
    name: Build CSS (Bun)
    runs-on: ubuntu-latest
    steps:
      - name: Check out the repo
        uses: actions/checkout@v6

      - name: Set up Bun
        uses: oven-sh/setup-bun@v2
        with:
          bun-version: latest

      - name: Install dependencies
        run: bun install --frozen-lockfile

      - name: Build Tailwind + daisyUI CSS
        run: bun run css:build

      - name: Fail if CSS build produced no output
        run: test -s src/main/resources/META-INF/resources/calit.css
```

- [ ] **Step 2: Lint**

```bash
cd /home/finkel/work_self/calit
./actionlint .github/workflows/ci.yml
```

Expected: no output (exit 0).

- [ ] **Step 3: Verify locally (if Bun installed)**

```bash
cd /home/finkel/work_self/calit
bun install --frozen-lockfile && bun run css:build && test -s src/main/resources/META-INF/resources/calit.css && echo CSS_OK
```

Expected: ends with `CSS_OK`. (Skip if Bun not installed locally — CI covers it; note in handoff.)

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add Bun CSS build job"
```

---

## Task 5: Add the `build` job — native multi-arch, push-by-digest

**Files:**
- Modify: `.github/workflows/ci.yml`

This job runs once per architecture on a **native** runner (no QEMU). Each run builds the full Dockerfile, pushes the image **by digest only** (no tags yet), and uploads its digest for the `merge` job. `buildkit-cache-dance` restores the Maven/Bun cache-mount contents (Task 2 targets) from `actions/cache` before the build and saves them after; layer cache uses `type=gha`, both scoped per-arch to avoid cross-arch collisions.

- [ ] **Step 1: Append the `build` job**

Add under `jobs:`:

```yaml
  build:
    name: Build image (${{ matrix.arch }})
    needs: [test, css]
    if: >-
      github.event_name == 'push' &&
      (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v'))
    runs-on: ${{ matrix.runner }}
    permissions:
      contents: read
      packages: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - arch: amd64
            runner: ubuntu-latest
            platform: linux/amd64
          - arch: arm64
            runner: ubuntu-24.04-arm
            platform: linux/arm64
    steps:
      - name: Check out the repo
        uses: actions/checkout@v6

      - name: Set up Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Restore build caches (.m2, bun)
        uses: actions/cache@v4
        with:
          path: |
            var-cache-m2
            var-cache-bun
          key: buildcache-${{ matrix.arch }}-${{ hashFiles('pom.xml', 'bun.lock') }}
          restore-keys: |
            buildcache-${{ matrix.arch }}-

      - name: Inject caches into BuildKit
        uses: reproducible-containers/buildkit-cache-dance@v3
        with:
          cache-map: |
            {
              "var-cache-m2": "/root/.m2",
              "var-cache-bun": "/root/.bun/install/cache"
            }
          skip-extraction: ${{ github.ref != 'refs/heads/main' && !startsWith(github.ref, 'refs/tags/v') }}

      - name: Build and push by digest
        id: build
        uses: docker/build-push-action@v6
        with:
          context: .
          file: ./Dockerfile
          platforms: ${{ matrix.platform }}
          cache-from: type=gha,scope=${{ matrix.arch }}
          cache-to: type=gha,mode=max,scope=${{ matrix.arch }}
          outputs: type=image,name=ghcr.io/${{ github.repository }},push-by-digest=true,name-canonical=true,push=true

      - name: Export digest
        run: |
          mkdir -p "${{ runner.temp }}/digests"
          digest="${{ steps.build.outputs.digest }}"
          touch "${{ runner.temp }}/digests/${digest#sha256:}"

      - name: Upload digest
        uses: actions/upload-artifact@v4
        with:
          name: digests-${{ matrix.arch }}
          path: ${{ runner.temp }}/digests/*
          if-no-files-found: error
          retention-days: 1
```

- [ ] **Step 2: Lint**

```bash
cd /home/finkel/work_self/calit
./actionlint .github/workflows/ci.yml
```

Expected: no output (exit 0).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: build native multi-arch image and push by digest"
```

---

## Task 6: Add the `merge` job — assemble the multi-arch manifest

**Files:**
- Modify: `.github/workflows/ci.yml`

Downloads both per-arch digests, computes the tag list with `metadata-action`, and creates one multi-arch manifest with `docker buildx imagetools create`.

- [ ] **Step 1: Append the `merge` job**

Add under `jobs:`:

```yaml
  merge:
    name: Merge multi-arch manifest
    needs: build
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - name: Download digests
        uses: actions/download-artifact@v4
        with:
          path: ${{ runner.temp }}/digests
          pattern: digests-*
          merge-multiple: true

      - name: Set up Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata (tags, labels)
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository }}
          tags: |
            type=edge,branch=main
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=sha,format=short

      - name: Create manifest list and push
        working-directory: ${{ runner.temp }}/digests
        run: |
          docker buildx imagetools create \
            $(jq -cr '.tags | map("-t " + .) | join(" ")' <<< "$DOCKER_METADATA_OUTPUT_JSON") \
            $(printf 'ghcr.io/${{ github.repository }}@sha256:%s ' *)

      - name: Inspect image
        run: |
          docker buildx imagetools inspect \
            "ghcr.io/${{ github.repository }}:${{ steps.meta.outputs.version }}"
```

Notes: `metadata-action`'s default `latest=auto` tags `:latest` only on non-prerelease semver tags. `$DOCKER_METADATA_OUTPUT_JSON` is exported by `metadata-action`. `jq` is preinstalled on `ubuntu-latest`.

- [ ] **Step 2: Lint**

```bash
cd /home/finkel/work_self/calit
./actionlint .github/workflows/ci.yml
```

Expected: no output (exit 0).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: merge per-arch digests into multi-arch manifest"
```

---

## Task 7: Add the `release` job + verify the full file

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Append the `release` job**

Add under `jobs:`:

```yaml
  release:
    name: Create GitHub Release
    needs: [test, css]
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Check out the repo
        uses: actions/checkout@v6
        with:
          fetch-depth: 0 # full history for generated release notes

      - name: Create release with generated notes
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          draft: false
          prerelease: ${{ contains(github.ref_name, '-') }}
```

- [ ] **Step 2: Confirm the assembled file matches this reference**

The complete `.github/workflows/ci.yml` must now be exactly the concatenation of: the header + `test` (Task 3), `css` (Task 4), `build` (Task 5), `merge` (Task 6), `release` (Task 7). Verify job order and that there is exactly one top-level `jobs:` key with five children: `test`, `css`, `build`, `merge`, `release`.

```bash
cd /home/finkel/work_self/calit
grep -nE '^  [a-z]+:' .github/workflows/ci.yml
```

Expected (job keys, in order): `test:`, `css:`, `build:`, `merge:`, `release:`.

- [ ] **Step 3: Lint the whole file**

```bash
cd /home/finkel/work_self/calit
./actionlint
```

Expected: scans `.github/workflows/*.yml`, no output (exit 0).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: create GitHub Release on v* tags"
```

---

## Task 8: End-to-end verification on GitHub

**Files:** none.

- [ ] **Step 1: Push the branch and open a PR**

```bash
cd /home/finkel/work_self/calit
git push -u origin HEAD
gh pr create --fill --base main
```

Expected: PR created; CI starts. On a PR, only `test` + `css` run (`build`/`merge`/`release` are `push`-gated and will show as skipped).

- [ ] **Step 2: Watch the PR run to green**

```bash
gh run watch --exit-status
```

Expected: `test` + `css` pass. If `test` fails on Dev Services, confirm the runner has Docker (all GitHub-hosted Linux runners do). If `setup-java` cannot resolve Liberica 26, STOP and raise it — do not downgrade below release 25.

- [ ] **Step 3: Merge, then watch the publish pipeline**

After merging to `main`:

```bash
gh run watch --exit-status
```

Expected: `test`, `css`, `build` (amd64 + arm64), `merge` all pass; `release` skipped (no tag). Image `:edge` pushed.

- [ ] **Step 4: Make the GHCR package public (one-time)**

The first push creates the package **private**. In the browser: GitHub → your profile/org → Packages → `calit` → Package settings → "Change visibility" → **Public**. Then verify anonymous pull:

```bash
docker pull ghcr.io/asm0dey/calit:edge
```

Expected: pulls without `docker login`.

- [ ] **Step 5: Verify the multi-arch manifest**

```bash
docker buildx imagetools inspect ghcr.io/asm0dey/calit:edge
```

Expected: manifest lists both `linux/amd64` and `linux/arm64`.

- [ ] **Step 6: Exercise the release path with a throwaway tag**

```bash
cd /home/finkel/work_self/calit
git tag v0.0.1-ci-test
git push origin v0.0.1-ci-test
gh run watch --exit-status
gh release view v0.0.1-ci-test
docker buildx imagetools inspect ghcr.io/asm0dey/calit:0.0.1-ci-test
```

Expected: a prerelease GitHub Release exists (hyphen in tag → prerelease); the `:0.0.1-ci-test` multi-arch image exists. `:latest` is NOT applied (prerelease).

- [ ] **Step 7: Clean up the throwaway tag**

```bash
cd /home/finkel/work_self/calit
gh release delete v0.0.1-ci-test --yes
git push origin :refs/tags/v0.0.1-ci-test
git tag -d v0.0.1-ci-test
```

Optionally delete the `:0.0.1-ci-test` image version from the GHCR package UI.

---

## Out of scope / handled elsewhere

- **Dependency updates:** Mend Renovate (configured externally). No Dependabot in this repo.
- **Branch protection / required status checks:** repo Settings, not a workflow file. After the pipeline is green, consider marking `test` + `css` as required checks on `main`.

## Self-Review notes

- **Spec coverage:** CI build+test (Tasks 3–4) ✓; Docker build+push to GHCR (Tasks 5–6) ✓; Release (Task 7) ✓; Dev Services for CI Postgres (Task 3, Docker on runner, no service block) ✓; GHCR registry, public (Task 8) ✓; multi-arch native runners (Task 5) ✓; dependency updates intentionally external (Renovate) ✓.
- **Gating:** `build` and `release` both `needs: [test, css]`; `merge` `needs: build`. Nothing publishes from a red commit.
- **Fork safety:** publish jobs `push`-gated → no fork-PR token failures.
- **Type/name consistency:** cache-mount targets `/root/.m2` + `/root/.bun/install/cache` are identical in Dockerfile (Task 2) and the `cache-map` (Task 5). Artifact names `digests-${arch}` (upload, Task 5) match the `digests-*` download pattern (Task 6). Image `ghcr.io/${{ github.repository }}` used identically in `build` and `merge`.
- **Action versions** verified current via Context7 (2026-06); Liberica 26 resolves (Java 26 GA).
- **No placeholders:** every job and Dockerfile edit is complete and committable as written.
