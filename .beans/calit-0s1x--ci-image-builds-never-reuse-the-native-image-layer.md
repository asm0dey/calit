---
# calit-0s1x
title: CI image builds never reuse the native-image layer
status: todo
type: task
priority: normal
created_at: 2026-09-12T22:25:10Z
updated_at: 2026-09-12T22:27:00Z
---

Noticed while waiting on the v1.25.0 release build. Filing the analysis; the fix needs measurement
first, because the most obvious candidate turns out to be worth the least.

## What busts the cache

Both `Dockerfile` and `Dockerfile.native` have the same shape:

```
:21  COPY mvnw pom.xml ./
:22  RUN --mount=type=cache,target=/root/.m2 ... go-offline
:25  COPY src/ src/                      (Dockerfile: :28)
:29  ARG APP_VERSION=dev                 (Dockerfile: :34)
:30  ARG GIT_COMMIT=dev                  (Dockerfile: :35)
:31  RUN printf ... > src/main/resources/git.properties
:66  RUN --mount=type=cache,target=/root/.m2 ... native-image build   (Dockerfile: :38 package)
```

`GIT_COMMIT` changes on every commit, so layer :31 busts every time and takes the expensive build
below it. The GraalVM compile therefore re-runs on every push by construction, cache or no cache.

`actions/cache` (ci.yml:195) keys on `hashFiles('pom.xml', 'bun.lock')`, so a version bump misses the
exact key and falls back to the `buildcache-<arch><suffix>-` restore-key. The `--mount=type=cache`
`.m2` keeps artifacts warm, so that part is re-resolution, not re-download. Not the problem.

## Candidates, roughly by payoff

1. **`COPY src/ src/` pulls in `src/test/`, which the image build never uses** — the build runs
   `-DskipTests`. A test-only commit currently invalidates the compile layer for nothing. Narrowing
   to `COPY src/main/ src/main/` is cheap and safe. Check nothing else under `src/` is needed first.
2. **Build native only for `v*` tags, not every push to `main`.** Ordinary main pushes would build
   the two jvm images and skip two native compiles. Changes what `edge`/`sha-*` mean for the native
   variant, so it needs a decision, not just an edit.
3. **Move the build-metadata write below the compile** — make `BuildInfo` prefer `APP_VERSION` /
   `GIT_COMMIT` env vars, falling back to `/git.properties` then `dev`, and set them as `ENV` in the
   runtime stage. Lowest payoff of the three: it does NOT speed up release builds, because the
   version bump in `pom.xml` already busts :21 above it, and it does not help ordinary commits
   because `COPY src/` busts first. It only helps commits that touch neither `src/` nor `pom.xml` —
   and ci.yml's `changes` job already skips images for `.beans/**`, root markdown, `docs/**`,
   `.agents/**` and `.claude/**`. Verify there is a real population of such commits before doing it.
   `BuildInfo` is footer-only display, so the value itself is low-risk to move.

## Todo

- [ ] Measure first: per-job durations from a recent `v*` run, split native compile vs everything else
- [ ] Confirm the image build needs nothing under `src/` except `src/main/`
- [ ] Candidate 1 if it holds
- [ ] Decide on candidate 2 (does `edge`-tagged native still need to exist?)
- [ ] Candidate 3 only if measurement justifies it
- [ ] Re-measure and record the before/after in this bean

## Candidate 4: version as a build argument, not a pom edit (user's idea)

Maven CI-friendly versions — `<version>${revision}</version>` plus `-Drevision=…`. Makes the git tag
the single source of truth and stops a release commit touching `pom.xml`.

**This is the missing half of candidate 3, and neither works alone:**

- version-as-argument alone: `:21 COPY mvnw pom.xml` caches, but `ARG GIT_COMMIT` at `:29` still
  busts `:31` and the compile below it.
- candidate 3 alone: metadata moves below the compile, but the pom version bump still busts `:21`.
- **both together**: a release commit touches neither `pom.xml` nor `src/`, so the native compile
  layer is genuinely reusable. Re-rank candidate 3 accordingly — it is not low-payoff when paired.

### The constraint that decides the design

The real version must NOT be an input to the compile layer. Passing `-Drevision=1.25.0` into the
`RUN ./mvnw package` command makes it part of the layer key and busts the cache again. So the compile
runs on a stable placeholder and the version is stamped in the RUNTIME stage only.

Costs of that:

- `BuildInfo` (footer) is easy — read `APP_VERSION` / `GIT_COMMIT` env vars, fall back to
  `/git.properties`, then `dev`. Display-only, and nothing else in `src/main` reads the version.
- **Quarkus' startup banner is not.** `calit 1.25.0 on JVM (powered by Quarkus …)` comes from the
  Maven project version and is build-time-fixed into the image; with a placeholder it reads
  `calit dev on JVM` in every deployment. Operator-visible. Decide whether that is acceptable, or
  whether the banner can be sourced at runtime, before committing to this.
- `target/*-runner` / `quarkus-app` globs in both Dockerfiles already tolerate a changed artifact
  name, so the placeholder does not break the COPY steps.
- `flatten-maven-plugin` is likely unnecessary: it matters for `install`/`deploy` of a pom containing
  `${revision}`, and this project only ever `package`s into an image. Confirm rather than assume.

### CI spots that scrape pom.xml

- `ci.yml:176` — `buildmeta.version` is `grep`ed out of `pom.xml`. A `v*` tag can use
  `${GITHUB_REF_NAME#v}`, but **a push to `main` has no tag** and still publishes `edge` / `sha-*`
  images that are versioned from the pom today. Needs a defined fallback.
- `ci.yml:397` — "Verify tag matches pom version". That guard exists precisely because the version
  lives in two places; moving it out of the pom makes the guard obsolete, not broken. Remove it in
  the same change rather than leaving it to fail.

### Knock-on

The release ritual in CLAUDE.md ("Bump pom.xml X.Y.Z") would change to "tag it" — the release commit
would touch only README image tags and the bean. Update CLAUDE.md in the same change.

- [ ] Decide on the startup-banner regression first — it gates the whole approach
- [ ] Define the version fallback for untagged `main` pushes
- [ ] Confirm `flatten-maven-plugin` is not needed for a package-only build
- [ ] Update CLAUDE.md's release ritual and drop the tag/pom guard
