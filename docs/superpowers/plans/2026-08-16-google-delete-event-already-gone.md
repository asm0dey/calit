# Tolerate already-deleted Google events on cancel (410/404) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cancelling a booking must succeed when the Google event was already deleted on Google's side (HTTP 410 Gone, or 404 Not Found), instead of 500-ing.

**Architecture:** Single change in `GoogleCalendarPort.deleteEvent`: catch `GoogleJsonResponseException` before the generic `IOException` catch; if the status is 410 or 404, log at INFO and return normally (the desired end state — no event on Google — already holds). Every other status keeps throwing `UncheckedIOException` exactly as today. Nothing in `BookingService` changes: it already treats a returning `deleteEvent` as success, so the local cancellation, the `BookingCancelled` event, and the cancellation email/.ics all proceed unchanged.

**Tech Stack:** Java 25 / Quarkus 3.38, google-api-services-calendar, JUnit 5 + Mockito, `@QuarkusTest`.

**Tracking bean:** `calit-qjqb` (GitHub issue https://github.com/asm0dey/calit/issues/118). Set it `in-progress` before Task 1 and `completed` after Task 3.

## Global Constraints

- Build JDK: `export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca` before any `./mvnw`/`mvn` command. The default JDK on this machine is 21 and fails with "release 25 not supported".
- Docker must be running — every `@QuarkusTest` boots a Dev Services Postgres.
- Formatting is a CI gate: Java is Spotless + palantir-java-format. The lefthook pre-commit hook runs `spotless:apply` on staged `*.java` automatically; if you commit outside the hook, run `mvn spotless:apply` first.
- No new user-facing strings → no `AppMessages`/`AdminMessages` keys, no `messages/*_{de,he}.properties` edits.
- No user-facing config/route/behavioural surface changes → no `docs-site` branch update for this fix. The changelog entry lands with the next release, not in this branch.
- Branch + PR: never push to `main`. Work on `fix/google-delete-410-gone`.
- Commit both the code and the bean file (`.beans/calit-qjqb--*.md`) in the same commit.

---

## File Structure

- `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java` — the fix. `deleteEvent` (currently at line 274-291) gains one `catch (GoogleJsonResponseException e)` branch. `GoogleJsonResponseException` is already imported (line 3) and this file already uses the same status-code branching in `handleCreateFailure`.
- `src/test/java/site/asm0dey/calit/google/DeleteEventAlreadyGoneTest.java` — new test class. Hand-builds a `GoogleCalendarPort` over a mocked `GoogleTokenService` + mocked `GoogleCalendarClientFactory`, exactly like the existing `GoogleCalendarListPortTest`, and seeds a write-target calendar row so `writeContext(ownerId)` resolves.

No other files change. `BookingService.cancelSingle` / `deleteGroupGoogleEvent` are untouched — the fix is deliberately inside the port so both the single- and group-cancel paths get it for free.

---

### Task 1: deleteEvent tolerates 410/404

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java:274-291`
- Test: `src/test/java/site/asm0dey/calit/google/DeleteEventAlreadyGoneTest.java` (create)

**Interfaces:**
- Consumes: `CalendarPort.deleteEvent(Long ownerId, String eventId)` (unchanged signature, `void`), `GoogleCalendarPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory)` — the existing public constructor, `GoogleTokenService.validAccessToken(GoogleCredential, Instant)`, `GoogleCalendarClientFactory.build(String accessToken)`.
- Produces: nothing new. `deleteEvent` keeps returning `void`; the only observable change is that it returns instead of throwing on 410/404.

- [ ] **Step 1: Create the branch and mark the bean in-progress**

```bash
cd /home/finkel/work_self/calit
git checkout -b fix/google-delete-410-gone
beans update calit-qjqb -s in-progress
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/site/asm0dey/calit/google/DeleteEventAlreadyGoneTest.java` with exactly this content:

```java
package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.calendar.Calendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Deleting an event that Google already deleted is idempotent from our side: the desired end state
 * (no event on Google) holds, so cancel must proceed. Anything else still fails loudly.
 *
 * <p>Collaborators are hand-built (mocked token service + client factory) like
 * {@link GoogleCalendarListPortTest}; the write-target row is seeded because deleteEvent resolves it
 * from the DB. The test method carries {@code @Transactional} since a hand-built port gets no CDI
 * interception.
 */
@QuarkusTest
class DeleteEventAlreadyGoneTest {

    /** Wire a port whose events.delete(...).execute() fails with the given exception. */
    private static GoogleCalendarPort portThatFailsWith(IOException failure) throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Delete delete = mock(Calendar.Events.Delete.class);
        when(delete.setSendUpdates(anyString())).thenReturn(delete);
        when(delete.execute()).thenThrow(failure);
        Calendar.Events events = mock(Calendar.Events.class);
        when(events.delete(anyString(), anyString())).thenReturn(delete);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    private static GoogleJsonResponseException status(int code, String reason) {
        return new GoogleJsonResponseException(new HttpResponseException.Builder(code, reason, new HttpHeaders()), null);
    }

    /** deleteEvent reads the owner's write target from the DB; give owner 1 one. */
    private static void seedWriteTarget(String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = 1L;
        wt.googleCredentialId = c.id;
        wt.googleCalendarId = "wt@example.com";
        wt.summary = "WT";
        wt.writeTarget = true;
        wt.persist();
    }

    @Test
    @Transactional
    void goneEventIsTreatedAsDeleted() throws IOException {
        seedWriteTarget("sub-gone");
        var port = portThatFailsWith(status(410, "Gone"));

        assertDoesNotThrow(() -> port.deleteEvent(1L, "evt-gone"));
    }

    @Test
    @Transactional
    void missingEventIsTreatedAsDeleted() throws IOException {
        seedWriteTarget("sub-missing");
        var port = portThatFailsWith(status(404, "Not Found"));

        assertDoesNotThrow(() -> port.deleteEvent(1L, "evt-missing"));
    }

    @Test
    @Transactional
    void otherGoogleFailuresStillThrow() throws IOException {
        seedWriteTarget("sub-boom");
        var port = portThatFailsWith(status(500, "Internal Server Error"));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.deleteEvent(1L, "evt-boom"));
        assertEquals("deleteEvent failed", thrown.getMessage());
    }

    @Test
    @Transactional
    void plainIoErrorStillThrows() throws IOException {
        seedWriteTarget("sub-timeout");
        var port = portThatFailsWith(new IOException("connect timed out"));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.deleteEvent(1L, "evt-timeout"));
        assertEquals("connect timed out", thrown.getCause().getMessage());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=DeleteEventAlreadyGoneTest
```

Expected: `goneEventIsTreatedAsDeleted` and `missingEventIsTreatedAsDeleted` FAIL with `java.io.UncheckedIOException: deleteEvent failed`. `otherGoogleFailuresStillThrow` and `plainIoErrorStillThrows` already PASS (they pin today's behaviour).

- [ ] **Step 4: Implement the fix**

In `src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java`, replace the body of `deleteEvent`:

```java
    @Override
    @Transactional
    public void deleteEvent(Long ownerId, String eventId) {
        var ctx = writeContext(ownerId);
        GoogleCalendar target = ctx.target();
        GoogleCredential cred = ctx.cred();
        try {
            // sendUpdates=all so Google emails the attendees the cancellation.
            client(cred)
                    .events()
                    .delete(target.googleCalendarId, eventId)
                    .setSendUpdates("all")
                    .execute();
        } catch (GoogleJsonResponseException e) {
            // 410 Gone / 404 Not Found: the event was already deleted on Google (e.g. by the owner,
            // directly in Google Calendar). The end state we wanted already holds, so deleting is
            // idempotent from the caller's side — let the local cancellation proceed. Every other
            // status still fails loudly.
            if (e.getStatusCode() != 410 && e.getStatusCode() != 404) {
                throw new UncheckedIOException("deleteEvent failed", e);
            }
            org.jboss.logging.Logger.getLogger(GoogleCalendarPort.class)
                    .infof("Google event %s was already deleted (HTTP %d); treating delete as done", eventId, e.getStatusCode());
        } catch (IOException e) {
            throw new UncheckedIOException("deleteEvent failed", e);
        }
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test -Dtest=DeleteEventAlreadyGoneTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Tick the bean todos**

```bash
beans update calit-qjqb \
  --body-replace-old "[ ] GoogleCalendarPort.deleteEvent: catch GoogleJsonResponseException with status 410 or 404 and return normally (log it); rethrow everything else as today" \
  --body-replace-new "[x] GoogleCalendarPort.deleteEvent: catch GoogleJsonResponseException with status 410 or 404 and return normally (log it); rethrow everything else as today"
beans update calit-qjqb \
  --body-replace-old "[ ] Test: cancel succeeds when the calendar port reports the event is already gone (fake/stub CalendarPort, no live Google needed)" \
  --body-replace-new "[x] Test: cancel succeeds when the calendar port reports the event is already gone (fake/stub CalendarPort, no live Google needed)"
```

If either `--body-replace-old` errors with "text not found", run `beans show calit-qjqb` and copy the todo line verbatim from the output.

- [ ] **Step 7: Commit**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
mvn spotless:apply -DspotlessFiles=.*GoogleCalendarPort.java,.*DeleteEventAlreadyGoneTest.java
git add src/main/java/site/asm0dey/calit/google/GoogleCalendarPort.java \
        src/test/java/site/asm0dey/calit/google/DeleteEventAlreadyGoneTest.java \
        .beans/calit-qjqb--cancelling-a-booking-500s-when-the-google-event-is.md
git commit -m "fix(google): treat 410/404 on deleteEvent as already deleted (closes #118)"
```

---

### Task 2: Record the sibling-write-path decision

The bean asks whether `updateEvent` / `updateEventDetails` need the same tolerance. They do **not**, and the reason must be written down rather than re-derived later: a delete against a gone event reaches the desired end state, but a *patch* against a gone event does not — the new time/summary was never applied anywhere, so silently swallowing 410 there would report a successful reschedule that did not happen. Those paths must keep failing loudly.

**Files:**
- Modify: `.beans/calit-qjqb--cancelling-a-booking-500s-when-the-google-event-is.md` (via the `beans` CLI)

**Interfaces:**
- Consumes: the bean from Task 1.
- Produces: nothing code-facing.

- [ ] **Step 1: Append the decision to the bean and tick its todo**

```bash
beans update calit-qjqb \
  --body-replace-old "[ ] Check the sibling write paths (updateEventDetails, move/patch) for the same already-gone hazard — at minimum note the decision in the bean" \
  --body-replace-new "[x] Check the sibling write paths (updateEventDetails, move/patch) for the same already-gone hazard — at minimum note the decision in the bean" \
  --body-append "## Decision: sibling write paths keep failing loudly

updateEvent / updateEventDetails (GoogleCalendarPort, both \`events().patch(...)\`) deliberately do NOT get the 410/404 tolerance. Delete is idempotent — a 410 means the end state we wanted (no event on Google) already holds. A patch is not: a 410 means the new time/summary/attendees were applied nowhere, so swallowing it would report a reschedule that never happened. If reschedule-onto-a-deleted-event turns out to hurt users, the fix is to re-create the event, not to ignore the error — separate bean."
```

- [ ] **Step 2: Commit**

```bash
git add .beans/calit-qjqb--cancelling-a-booking-500s-when-the-google-event-is.md
git commit -m "chore(beans): record why patch paths keep failing on 410"
```

---

### Task 3: Full suite, PR, close the bean

**Files:**
- Modify: `.beans/calit-qjqb--cancelling-a-booking-500s-when-the-google-event-is.md` (via the `beans` CLI)

**Interfaces:**
- Consumes: the branch from Tasks 1-2.
- Produces: the merged-ready PR.

- [ ] **Step 1: Run the whole suite**

Docker must be running.

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw test
```

Expected: `BUILD SUCCESS`, zero failures/errors. The cancel-path tests that must stay green are `RescheduleCancelTest` and `GroupCancelRescheduleTest` (both mock `CalendarPort`, so they exercise `BookingService`'s side of cancel — booking rows go `CANCELLED`, `BookingCancelled` fires, the cancellation email/.ics still goes out). If anything fails, fix it before continuing; do not proceed to Step 2 with a red suite.

- [ ] **Step 2: Verify formatting (the CI gate)**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/26.0.1-librca
./mvnw spotless:check
```

Expected: `BUILD SUCCESS`. On failure run `./mvnw spotless:apply`, then `git add -u && git commit --amend --no-edit`.

- [ ] **Step 3: Tick the last bean todo, add the summary, close the bean**

```bash
beans update calit-qjqb \
  --body-replace-old "[ ] Verify the booking still ends up cancelled locally and the cancellation email/.ics still goes out" \
  --body-replace-new "[x] Verify the booking still ends up cancelled locally and the cancellation email/.ics still goes out" \
  --body-append "## Summary of Changes

GoogleCalendarPort.deleteEvent now catches GoogleJsonResponseException ahead of the generic IOException catch: status 410 or 404 logs at INFO and returns (the event is already gone on Google, so the delete is idempotent); every other status still throws UncheckedIOException(\"deleteEvent failed\") as before. Fixing it in the port covers both BookingService.cancelSingle and deleteGroupGoogleEvent with no service-side change, so the booking still goes CANCELLED locally and the cancellation email/.ics still goes out (covered by RescheduleCancelTest / GroupCancelRescheduleTest).

New test: DeleteEventAlreadyGoneTest — 410 and 404 do not throw, 500 and a plain IOException still do." \
  -s completed
```

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin fix/google-delete-410-gone
gh pr create --title "fix(google): treat 410/404 on deleteEvent as already deleted" --body "$(cat <<'EOF'
Cancelling a booking 500'd when the Google event had already been deleted directly in Google Calendar: `GoogleCalendarPort.deleteEvent` wrapped every `IOException` — including a 410 Gone — into `UncheckedIOException`, aborting the whole cancel transaction even though the desired end state (no event on Google) already held.

410 and 404 are now logged at INFO and treated as success; every other status still fails loudly. Fixed in the port, so single-cancel and group-cancel both get it.

`updateEvent` / `updateEventDetails` deliberately keep failing on 410 — a patch that hits a deleted event applied nothing, so swallowing it would report a reschedule that never happened.

Closes #118

Test: `DeleteEventAlreadyGoneTest` (410/404 pass through, 500 and plain IOException still throw). Full suite green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01XA8bawWzsifDvxvF3k5QNU
EOF
)"
```

Expected: `gh` prints the PR URL. Report that URL as the final output.
