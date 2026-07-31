# Invitee Timezone Picker — Full IANA List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let invitees pick ANY IANA timezone (e.g. `Asia/Jerusalem`) in the public booking-page tz-picker, not just the 19 hardcoded zones.

**Architecture:** The invitee tz-picker (`#tz-picker`) is populated purely client-side by `Layout.TZ_SCRIPT`. Today it iterates a hardcoded `ZONES` array of 19 zones. Replace that array's source with the browser's native `Intl.supportedValuesOf('timeZone')` — the full canonical IANA list the browser can actually render via `toLocaleString({timeZone})`. Keep the existing curated array as a fallback for pre-2022 browsers that lack `supportedValuesOf`. No server changes: owner-facing dropdowns (`settings`, `meSetup`) already use `ZoneId.getAvailableZoneIds()` and already include Jerusalem.

**Tech Stack:** Java 25 / Quarkus, inline vanilla JS in `Layout.java`, RestAssured `@QuarkusTest` (asserts on marker strings — cannot execute JS).

## Global Constraints

- Progressive enhancement: this picker is already JS-only enhancement (times render server-side in the owner's zone; the picker reformats client-side). No JS-off regression possible here.
- Tests cannot run JS — assert only on rendered script text / stable marker comments.
- No new user-facing translatable strings (IANA zone IDs are not localized) → no `messages/*.properties` change.
- Keep the `CALIT_TZ_REFORMAT` marker comment intact (three tests assert its presence).

---

### Task 1: Source the invitee tz-picker from the browser's full IANA list

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java:31-39` (the `ZONES` array inside `TZ_SCRIPT`)
- Test: `src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java` (add one assertion)

**Interfaces:**
- Consumes: nothing new.
- Produces: no Java signature change. `Layout.TZ_SCRIPT` remains a `public static final String`; only its embedded JS changes. Adds a new stable marker substring `CALIT_TZ_FULL_LIST` and the literal `supportedValuesOf` to the rendered script, which the test asserts on.

- [ ] **Step 1: Write the failing assertion**

In `src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java`, find the block that asserts the TZ_SCRIPT marker (around line 82-83):

```java
                // TZ_SCRIPT stable marker
                .body(containsString("CALIT_TZ_REFORMAT"))
```

Add, immediately after that `.body(...)` line, an assertion that the picker now sources the full browser IANA list:

```java
                // Invitee tz-picker sources the full IANA list from the browser (incl. Asia/Jerusalem)
                .body(containsString("CALIT_TZ_FULL_LIST"))
                .body(containsString("supportedValuesOf"))
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=$(ls -d ~/.sdkman/candidates/java/*librca 2>/dev/null | tail -1)
./mvnw test -Dtest=LayoutLocaleMarkerTest
```
Expected: FAIL — response body does not contain `CALIT_TZ_FULL_LIST` / `supportedValuesOf` (still the old hardcoded array).

- [ ] **Step 3: Replace the hardcoded ZONES source in TZ_SCRIPT**

In `src/main/java/site/asm0dey/calit/web/Layout.java`, replace lines 31-39 — the `var ZONES = [ ... ];` literal followed by the `detected`/`unshift` lines:

```java
              var ZONES = [
                'America/Los_Angeles','America/Denver','America/Chicago','America/New_York',
                'America/Sao_Paulo','UTC','Europe/London','Europe/Amsterdam','Europe/Berlin',
                'Europe/Paris','Europe/Madrid','Europe/Athens','Africa/Johannesburg',
                'Asia/Dubai','Asia/Kolkata','Asia/Singapore','Asia/Tokyo',
                'Australia/Sydney','Pacific/Auckland'
              ];
              var detected = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
              if (ZONES.indexOf(detected) < 0) { ZONES.unshift(detected); }
```

with:

```java
              /* CALIT_TZ_FULL_LIST — full canonical IANA list from the browser; curated fallback for pre-2022 browsers. */
              var ZONES;
              try { ZONES = Intl.supportedValuesOf('timeZone').slice(); }
              catch (e) {
                ZONES = [
                  'America/Los_Angeles','America/Denver','America/Chicago','America/New_York',
                  'America/Sao_Paulo','UTC','Europe/London','Europe/Amsterdam','Europe/Berlin',
                  'Europe/Paris','Europe/Madrid','Europe/Athens','Africa/Johannesburg',
                  'Asia/Dubai','Asia/Kolkata','Asia/Singapore','Asia/Tokyo',
                  'Australia/Sydney','Pacific/Auckland'
                ];
              }
              var detected = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
              if (ZONES.indexOf(detected) < 0) { ZONES.unshift(detected); }
```

Everything downstream (the `ZONES.forEach` that builds `<option>`s, the `detected` preselect) is unchanged — it already iterates `ZONES`.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME=$(ls -d ~/.sdkman/candidates/java/*librca 2>/dev/null | tail -1)
./mvnw test -Dtest=LayoutLocaleMarkerTest
```
Expected: PASS — body now contains `CALIT_TZ_REFORMAT`, `CALIT_TZ_FULL_LIST`, and `supportedValuesOf`.

- [ ] **Step 5: Run the tz-marker regression tests to confirm no marker broke**

Run:
```bash
export JAVA_HOME=$(ls -d ~/.sdkman/candidates/java/*librca 2>/dev/null | tail -1)
./mvnw test -Dtest=LayoutLocaleMarkerTest,BookPageTest,ManageBookingTest,BookingPostTest
```
Expected: PASS — all four still find `CALIT_TZ_REFORMAT`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/Layout.java \
        src/test/java/site/asm0dey/calit/web/LayoutLocaleMarkerTest.java
git commit -m "feat(booking): invitee tz-picker offers full IANA list (e.g. Asia/Jerusalem)"
```

---

## Notes / follow-ups (not blocking)

- **Docs:** UX-only enhancement, no env var / route / config change. Add a one-line changelog entry on the next release (`docs-site` branch, `releases/changelog.md`) — not part of this task.
- **Manual browser check:** load a public booking page in a browser whose OS zone is NOT Jerusalem; confirm `Asia/Jerusalem` is selectable in the tz-picker and reformats times correctly. RestAssured can't cover this (no JS).
