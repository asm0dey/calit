# Outbound Notification Channels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each calit owner register their own notification channel URLs (Telegram, Slack, Discord, ntfy, Gotify, generic webhook, …) and receive every booking event on them, routed per meeting type.

**Architecture:** `org.alexmond:notify4j-core` resolves a channel from an Apprise-style URL at runtime. URLs live in a new `notification_channel` table, encrypted at rest with the existing `EncryptedStringConverter`, owner-scoped like every other tenant row. A synchronous CDI observer (`AFTER_SUCCESS`) loads the booking snapshot, resolves each host's channels and renders the message in that host's locale; it then fires one async CDI event per channel row, and the async observer does one blocking `Notifications.sendOnce` plus one timestamp write.

**Tech Stack:** Quarkus 3.38, Java 25, Panache/Hibernate, Flyway, Qute + daisyUI, notify4j-core 1.1.1, JUnit 5 + RestAssured + `com.sun.net.httpserver.HttpServer`.

**Spec:** `docs/superpowers/specs/2026-09-12-outbound-notifications-design.md`

**Bean:** `calit-6rzr` (in-progress). Keep its todo list in step with this plan; commit bean file changes with the code.

## Global Constraints

- Every tenant row carries `owner_id`; **every** query filters by the current owner. `CurrentOwner.require()` throws 401 if unset. One user must never read or write another's channels.
- Java toolchain: `export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca` before any `./mvnw` invocation, otherwise the build fails with `release version 25 not supported`. `-o` (offline) is faster.
- Docker must be running for `mvn test` (Dev Services Postgres) and for the native spike.
- Never edit an applied Flyway migration. The next free version is **V32**.
- Every new or changed user-facing string ships its `de` **and** `he` translation in the same change (`src/main/resources/messages/{msg,adm}_{de,he}.properties`), with identical `{placeholder}` names across locales. `MultiHostMessageParityTest` fails the build if a key is missing or orphaned.
- Every POST form carries `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">`. CSRF is disabled in `%test` but required in prod.
- Progressive enhancement: every feature works with JavaScript disabled. JS may only enhance.
- Java is formatted by Spotless + palantir-java-format; run `./mvnw -o spotless:apply` before committing. `verify` fails on unformatted code.
- Channel URLs are **secret-bearing**: never log them, never render them raw. Use `ChannelCatalog.redact(url)` for anything a human or a log sees.
- `mvn test` must be fully green (0 failures, 0 errors) before the branch becomes a PR. Not "the tests I touched" — the whole suite.
- Branch is `outbound-notifications`; do not push to `main`.
- **Any language-level operation goes through the IDE (MCP Steroid) or the LSP, never through text tools.** That covers finding references and call sites, navigating types and hierarchies, checking errors, and every edit to an existing Java file — `grep`/`sed`/the native `Edit` tool answer from text, and writing straight to disk leaves IntelliJ's VFS, PSI and search indices stale, so the next semantic operation answers from a stale model. Type-level changes (rename, move, safe delete, extract) use IntelliJ's own refactoring processors so the IDE owns reference updating; where a change really is textual, do it through `steroid_execute_code`'s `VfsUtil.saveText` recipe (which refreshes the VFS), and prefer structural search/replace over a regex when the IDE offers it. `grep` stays fine for non-language files — properties, SQL, templates, YAML.
- Skipping tests in a Maven invocation is `-Dmaven.test.skip=true`, not `-DskipTests` — it skips test *compilation* too, so a package/native build does not wait on javac for the test tree.

## Spec Deviations (decided while writing this plan)

1. **Masked URL round-trip.** The spec says existing rows render masked via `catalog.parse()` and come back through `catalog.recompose()`. `recompose(priorUrl, editedValues)` needs a per-field value map, which a one-field URL form does not have, and `buildUrl(ParsedChannel)` explicitly *rejects* a still-masked secret — so a masked whole-URL string cannot be rebuilt. The one-field form therefore renders `catalog.redact(url)` and treats a submitted value byte-equal to that redaction as "unchanged, keep the stored URL". Same user-visible property (the real secret never round-trips through the browser), achievable with the one field the spec's UI section commits to.
2. **Private-target check is scoped to host-bearing channels.** Resolving the authority of `telegram://<bot-token>/<chat-id>` would send a bot token to a DNS resolver. The check therefore runs only when the channel's descriptor has a `FieldType.URL` field or the URL uses the `+http` cleartext transport — exactly the self-hosted channels the flag exists for.
3. **Titles reuse the existing `email_*_subject` keys** rather than adding eleven parallel `channel_*_title` keys. Only the three events with no email subject of their own (guest declined, guest removed, co-host consent) get a new title key.

---

## File Structure

New package `site.asm0dey.calit.notify`:

| File | Responsibility |
|---|---|
| `NotificationChannel.java` | Panache entity for one owner's channel URL (encrypted) + delivery timestamps |
| `NotificationChannelMeetingType.java` | Panache entity for the per-meeting-type override link rows |
| `ChannelRouter.java` | The routing rule: override links for this host, else all of this host's channels |
| `ChannelPolicy.java` | Scheme allowlist + private-target rule, enforced at save time and again at send time |
| `NotifyConfig.java` | `calit.notify.*` config accessors (mockable bean, like `CaptchaProviderConfig`) |
| `HostNotification.java` | Sealed interface + 11 records + `Host` recipient record + `kind()` |
| `ChannelMessageRenderer.java` | `HostNotification` → notify4j `Message`, in the recipient's locale/zone |
| `NotificationDispatcher.java` | 11 `AFTER_SUCCESS` observers; loads, routes, renders, fires async |
| `ChannelDelivery.java` | Async event payload: `(channelId, url, message)` |
| `ChannelSender.java` | `@ObservesAsync`: `sendOnce` + stamp `last_success_at` / `last_failure_at` |
| `ChannelAdmin.java` | Save/delete/test operations behind the `/me/settings` handlers |

New in `site.asm0dey.calit.email`:

| File | Responsibility |
|---|---|
| `BookingSnapshot.java` | The record promoted out of `EmailService.Loaded` (+ nested `HostDelivery`) |
| `BookingSnapshotLoader.java` | `read`/`load` promoted out of `EmailService`, now a CDI bean |

Modified: `pom.xml`, `Dockerfile.native` (only if the spike demands a build arg), `src/main/resources/application.properties`, `.env.example`, `EmailService.java`, `AppMessages.java`, `AdminMessages.java`, the four locale property files, `AdminResource.java`, `SharedMeetingsResource.java`, `templates/AdminResource/settings.html`, `templates/AdminResource/meetingTypeDetail.html`, `templates/SharedMeetingsResource/sharedAvailability.html`.

---

## Task 1: Native-image spike (gate)

Throwaway. The output is an answer, not code we keep. **Do not start Task 2 until this passes.**

**Files:**
- Modify: `pom.xml` (add the dependency — this part is kept)
- Create: `src/main/java/site/asm0dey/calit/notify/SpikeResource.java` (deleted in the last step)
- Create: `/tmp/finkel/spike-sink.py` (scratch, never committed)
- Modify (only if the build fails): `Dockerfile.native`

**Interfaces:**
- Consumes: nothing.
- Produces: the `org.alexmond:notify4j-core:1.1.1` dependency in `pom.xml`, and a recorded pass/fail verdict on the bean.

- [ ] **Step 1: Add the dependency**

In `pom.xml`, next to the other third-party dependencies (after the `bcprov-jdk18on` block):

```xml
    <dependency>
      <groupId>org.alexmond</groupId>
      <artifactId>notify4j-core</artifactId>
      <version>1.1.1</version>
    </dependency>
```

- [ ] **Step 2: Add the throwaway probe**

Create `src/main/java/site/asm0dey/calit/notify/SpikeResource.java`:

```java
package site.asm0dey.calit.notify;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.alexmond.notify4j.ChannelCatalog;
import org.alexmond.notify4j.Message;
import org.alexmond.notify4j.Notifications;
import org.alexmond.notify4j.SendResult;

/** THROWAWAY native-image spike probe. Deleted in the last step of Task 1. */
@Path("/spike/notify")
@PermitAll
public class SpikeResource {

    /** Prints the field keys of a scheme so the caller can build a real URL without guessing. */
    @GET
    @Path("/fields")
    public String fields(@QueryParam("scheme") String scheme) {
        return ChannelCatalog.standard()
                .describe(scheme)
                .map(d -> d.fields().toString())
                .orElse("unknown scheme");
    }

    /** Builds a cleartext-http webhook URL for hostPort, e.g. "192.168.1.10:9099/hook". */
    @GET
    @Path("/url")
    public String url(@QueryParam("hostPort") String hostPort) {
        return ChannelCatalog.standard().buildUrl("webhook", Map.of("url", hostPort), Set.of(), true);
    }

    @GET
    public String send(@QueryParam("url") String url) {
        SendResult r = Notifications.sendOnce(List.of(url), Message.of("calit spike", "hello from native"));
        return r.attempted() + "/" + r.sent() + "/" + r.failed();
    }
}
```

- [ ] **Step 3: Verify the field key on the JVM first**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o -Dmaven.test.skip=true package
```

Expected: `BUILD SUCCESS`. If `buildUrl("webhook", Map.of("url", …))` turns out to use a different field key, `GET /spike/notify/fields?scheme=webhook` (run in dev mode) names the real one — fix the `Map.of` key before continuing. This is exactly why the `/fields` endpoint exists.

- [ ] **Step 4: Start a POST sink on the host**

```bash
mkdir -p /tmp/finkel/claude-1000/spike && cat > /tmp/finkel/claude-1000/spike/sink.py <<'PY'
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        print("GOT POST", self.path, self.rfile.read(n).decode(), flush=True)
        self.send_response(200); self.end_headers()
HTTPServer(("0.0.0.0", 9099), H).serve_forever()
PY
python3 /tmp/finkel/claude-1000/spike/sink.py &
```

- [ ] **Step 5: Build the native image**

```bash
docker build -f Dockerfile.native -t calit:spike .
```

Expected: the build completes. **Predicted failure:** `UnsupportedFeatureException` naming `org.alexmond.notify4j.HttpClientConfig` — its `private static final HttpClientConfig DEFAULT` builds an `HttpClient`, which cannot live in the image heap. If and only if that fires, append `,--initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig` to the `-Dquarkus.native.additional-build-args=` value in `Dockerfile.native` (it is one comma-separated list; keep every existing item) and rebuild.

- [ ] **Step 6: Run the binary and prove a POST leaves the process**

```bash
docker network create spike || true
docker run -d --name spikepg --network spike \
  -e POSTGRES_USER=calit -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=calit postgres:16-alpine
sleep 5
docker run -d --name spikeapp --network spike -p 8080:8080 --add-host=host.docker.internal:host-gateway \
  -e DB_URL=jdbc:postgresql://spikepg:5432/calit -e DB_USER=calit -e DB_PASSWORD=pw \
  -e SESSION_ENCRYPTION_KEY=spike-session-key-0123456789-abcdef \
  -e TOKEN_ENCRYPTION_KEY=1111111111111111111111111111111111111111111111111111111111111111 \
  -e APP_BASE_URL=http://localhost:8080 -e MAIL_HOST=localhost -e QUARKUS_MAILER_MOCK=true \
  -e GOOGLE_OAUTH_CLIENT_ID=dummy -e GOOGLE_OAUTH_CLIENT_SECRET=dummy \
  -e GOOGLE_OAUTH_STATE_SECRET=spike-state-secret-0123456789-abcd \
  calit:spike
for i in $(seq 1 120); do curl -fs http://localhost:8080/q/health/ready >/dev/null && break; sleep 1; done
URL=$(curl -s "http://localhost:8080/spike/notify/url?hostPort=host.docker.internal:9099/hook")
echo "built url: $URL"
curl -s "http://localhost:8080/spike/notify?url=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$URL")"
```

Expected: the curl prints `1/1/0`, and the sink terminal prints a `GOT POST /hook {...}` line.

- [ ] **Step 7: Check the native log gate**

```bash
docker logs spikeapp 2>&1 | grep -iE 'NoSuchMethodException|InstantiationException|no accessible default constructor|UnsupportedFeatureException' && echo "GATE FAILED" || echo "gate clean"
```

Expected: `gate clean`. All three criteria (build completes, POST delivered, log clean) must hold. If the build fails in a way no build argument fixes, **stop the plan** — the library decision reopens per the spec, and the fallback is hand-rolling the channels.

- [ ] **Step 8: Tear down and delete the throwaway**

```bash
docker rm -f spikeapp spikepg; docker network rm spike
pkill -f spike/sink.py
rm src/main/java/site/asm0dey/calit/notify/SpikeResource.java
```

- [ ] **Step 9: Record the verdict and commit**

```bash
beans update calit-6rzr --body-append "## Native spike

notify4j-core 1.1.1 native build: PASS. POST delivered from the native binary; reflection/init log gate clean. Build arg needed: <none | --initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig>."
git add pom.xml Dockerfile.native .beans/
git commit -m "build: add notify4j-core after native-image spike"
```

---

## Task 2: Schema and entities

**Files:**
- Create: `src/main/resources/db/migration/V32__notification_channel.sql`
- Create: `src/main/java/site/asm0dey/calit/notify/NotificationChannel.java`
- Create: `src/main/java/site/asm0dey/calit/notify/NotificationChannelMeetingType.java`
- Test: `src/test/java/site/asm0dey/calit/notify/NotificationChannelTest.java`

**Interfaces:**
- Consumes: `site.asm0dey.calit.crypto.EncryptedStringConverter` (JPA `AttributeConverter<String,String>`, applied with `@Convert(converter = …)`, stored form `"enc:v1:" + base64(...)`).
- Produces:
  - `NotificationChannel` with public fields `Long id, Long ownerId, String url, String label, Instant createdAt, Instant lastSuccessAt, Instant lastFailureAt`; statics `List<NotificationChannel> forOwner(Long ownerId)` and `NotificationChannel ownedBy(Long id, Long ownerId)`.
  - `NotificationChannelMeetingType` with public fields `Long id, Long channelId, Long meetingTypeId`; statics `List<NotificationChannelMeetingType> forType(Long meetingTypeId)`, `Set<Long> linkedChannelIds(Long meetingTypeId)`, `void replaceLinks(Long meetingTypeId, Collection<Long> ownChannelIds, Collection<Long> keepChannelIds)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/notify/NotificationChannelTest.java`:

```java
package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The channel table holds a secret-bearing URL: it must be encrypted at rest (SEC-SECRET-02, same
 * mechanism as GoogleCredential's tokens) and every finder must be owner-scoped.
 */
@QuarkusTest
class NotificationChannelTest {

    private static final String TELEGRAM = "telegram://111:AAbbCC/222333";

    @Inject
    EntityManager em;

    private Long persist(long ownerId, String url, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = url;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    @Test
    void urlIsEncryptedAtRestAndDecryptsOnRead() {
        Long id = persist(1L, TELEGRAM, "Phone");

        String raw = QuarkusTransaction.requiringNew()
                .call(() -> (String) em.createNativeQuery("select url from notification_channel where id = :id")
                        .setParameter("id", id)
                        .getSingleResult());
        assertTrue(raw.startsWith("enc:v1:"), "url column must hold ciphertext, was: " + raw);
        assertFalse(raw.contains("AAbbCC"), "the bot token must not appear in the column");

        String readBack = QuarkusTransaction.requiringNew()
                .call(() -> ((NotificationChannel) NotificationChannel.findById(id)).url);
        assertEquals(TELEGRAM, readBack);
    }

    @Test
    void forOwnerNeverReturnsAnotherOwnersChannel() {
        persist(1L, TELEGRAM, "Mine");
        persist(2L, "slack://T00/B00/xxxx", "Theirs");

        var mine = QuarkusTransaction.requiringNew().call(() -> NotificationChannel.forOwner(1L));
        assertEquals(1, mine.size());
        assertEquals("Mine", mine.getFirst().label);
    }

    @Test
    void ownedByRejectsAnotherOwnersId() {
        Long theirs = persist(2L, "slack://T00/B00/xxxx", "Theirs");
        assertNull(QuarkusTransaction.requiringNew().call(() -> NotificationChannel.ownedBy(theirs, 1L)));
        assertNotNull(QuarkusTransaction.requiringNew().call(() -> NotificationChannel.ownedBy(theirs, 2L)));
    }
}
```

- [ ] **Step 2: Run it to watch it fail**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest=NotificationChannelTest
```

Expected: compilation failure — `cannot find symbol: class NotificationChannel`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V32__notification_channel.sql`:

```sql
-- Feature #194: per-owner outbound notification channels, delivered via notify4j.
-- url is secret-bearing (bot tokens, webhook secrets) and is encrypted at rest by
-- EncryptedStringConverter, so it is TEXT and carries NO unique constraint: AES-GCM uses a random
-- IV, so the same URL encrypts differently every time and ciphertext comparison is meaningless.
CREATE TABLE notification_channel (
    id              BIGSERIAL   PRIMARY KEY,
    owner_id        BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    url             TEXT        NOT NULL,          -- secret-bearing, encrypted at rest
    label           VARCHAR(64),                   -- owner's own name; NOT unique
    created_at      TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ
);
CREATE INDEX idx_notification_channel_owner ON notification_channel (owner_id);

-- Per-meeting-type OVERRIDE, modelled on meeting_type_host (V20).
-- No rows for a (host, type) pair = that host inherits ALL of their own channels.
CREATE TABLE notification_channel_meeting_type (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      BIGINT NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    meeting_type_id BIGINT NOT NULL REFERENCES meeting_type(id)         ON DELETE CASCADE,
    CONSTRAINT uq_ncmt UNIQUE (channel_id, meeting_type_id)
);
CREATE INDEX idx_ncmt_channel ON notification_channel_meeting_type (channel_id);
CREATE INDEX idx_ncmt_type    ON notification_channel_meeting_type (meeting_type_id);
```

- [ ] **Step 4: Write the entities**

Create `src/main/java/site/asm0dey/calit/notify/NotificationChannel.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import site.asm0dey.calit.crypto.EncryptedStringConverter;

/**
 * One owner's outbound notification channel: an Apprise-style URL notify4j resolves to a concrete
 * channel at send time. The presence of a row IS the owner's consent — there is no enabled flag,
 * "turn it off" is "delete the row".
 */
@Entity
@Table(name = "notification_channel")
public class NotificationChannel extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** Secret-bearing (bot tokens, webhook secrets): encrypted at rest, never logged, never rendered raw. */
    @Column(nullable = false, columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    public String url;

    /** The owner's own name for this channel. Plain text — holds no secret, so the override list can
     * render without decrypting every URL. Defaulted from the channel's display name when left blank. */
    @Column(length = 64)
    public String label;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_success_at")
    public Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    public Instant lastFailureAt;

    public static List<NotificationChannel> forOwner(Long ownerId) {
        return list("ownerId = ?1 order by id", ownerId);
    }

    /** This owner's channel by id, or null — the owner-scoping guard for every /me handler. */
    public static NotificationChannel ownedBy(Long id, Long ownerId) {
        return find("id = ?1 and ownerId = ?2", id, ownerId).firstResult();
    }
}
```

Create `src/main/java/site/asm0dey/calit/notify/NotificationChannelMeetingType.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A per-meeting-type routing OVERRIDE. No rows for a (host, type) pair means that host inherits all
 * of their own channels; the "belonging to THIS host" half is applied by {@link ChannelRouter}, so
 * one host narrowing a co-hosted type never changes another host's delivery.
 */
@Entity
@Table(name = "notification_channel_meeting_type")
public class NotificationChannelMeetingType extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public Long channelId;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    public static List<NotificationChannelMeetingType> forType(Long meetingTypeId) {
        return list("meetingTypeId", meetingTypeId);
    }

    public static Set<Long> linkedChannelIds(Long meetingTypeId) {
        return forType(meetingTypeId).stream().map(l -> l.channelId).collect(Collectors.toSet());
    }

    /**
     * Replace THIS host's links for one meeting type: drops every link naming a channel in
     * {@code ownChannelIds} and re-creates one per id in {@code keepChannelIds}. Another host's
     * links on the same type are never touched, because they name channels this host does not own.
     */
    public static void replaceLinks(
            Long meetingTypeId, Collection<Long> ownChannelIds, Collection<Long> keepChannelIds) {
        if (!ownChannelIds.isEmpty()) {
            delete("meetingTypeId = ?1 and channelId in ?2", meetingTypeId, ownChannelIds);
        }
        for (Long channelId : keepChannelIds) {
            if (!ownChannelIds.contains(channelId)) continue; // never link a channel this host does not own
            var link = new NotificationChannelMeetingType();
            link.meetingTypeId = meetingTypeId;
            link.channelId = channelId;
            link.persist();
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./mvnw -o test -Dtest=NotificationChannelTest
```

Expected: 3 tests, 0 failures.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -o spotless:apply
git add src/main/resources/db/migration/V32__notification_channel.sql \
        src/main/java/site/asm0dey/calit/notify/ \
        src/test/java/site/asm0dey/calit/notify/
git commit -m "feat(notify): notification_channel schema and entities"
```

---

## Task 3: Channel routing

**Files:**
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelRouter.java`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelRouterTest.java`

**Interfaces:**
- Consumes: `NotificationChannel.forOwner(Long)`, `NotificationChannelMeetingType.linkedChannelIds(Long)` from Task 2.
- Produces: `ChannelRouter` (`@ApplicationScoped`) with `List<NotificationChannel> channelsFor(Long hostOwnerId, Long meetingTypeId)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelRouterTest.java`:

```java
package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The routing rule: link rows for this meeting type that belong to THIS host's channels select
 * exactly those channels; no such rows means this host inherits all of their own channels. The
 * per-host scoping is what lets one host narrow a co-hosted type while their co-host keeps
 * inheriting.
 */
@QuarkusTest
class ChannelRouterTest {

    private static final long HOST_A = 1L;
    private static final long HOST_B = 2L;

    @Inject
    ChannelRouter router;

    private Long channel(long ownerId, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = "ntfy+http://localhost:1/" + label;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    private MeetingType sharedType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(HOST_A, "Creator");
            MultiHostFixtures.settings(HOST_B, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(HOST_A, HOST_B, "routed", 30, false);
        });
    }

    @Test
    void noLinkRowsMeansInheritEveryChannelOfThatHost() {
        channel(HOST_A, "phone");
        channel(HOST_A, "slack");
        MeetingType type = sharedType();

        List<NotificationChannel> picked = router.channelsFor(HOST_A, type.id);

        assertEquals(2, picked.size());
    }

    @Test
    void linkRowsSelectExactlyThoseChannels() {
        Long phone = channel(HOST_A, "phone");
        channel(HOST_A, "slack");
        MeetingType type = sharedType();
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, List.of(phone), List.of(phone)));

        List<NotificationChannel> picked = router.channelsFor(HOST_A, type.id);

        assertEquals(1, picked.size());
        assertEquals("phone", picked.getFirst().label);
    }

    @Test
    void oneHostsOverrideDoesNotNarrowTheCoHost() {
        Long aPhone = channel(HOST_A, "a-phone");
        channel(HOST_A, "a-slack");
        channel(HOST_B, "b-phone");
        channel(HOST_B, "b-slack");
        MeetingType type = sharedType();
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(
                        type.id, List.of(aPhone), List.of(aPhone)));

        assertEquals(1, router.channelsFor(HOST_A, type.id).size(), "host A narrowed to their override");
        assertEquals(2, router.channelsFor(HOST_B, type.id).size(), "host B still inherits all of theirs");
    }

    @Test
    void anotherOwnersChannelIsNeverSelected() {
        channel(HOST_B, "b-phone");
        MeetingType type = sharedType();

        assertTrue(router.channelsFor(HOST_A, type.id).isEmpty());
    }

    @Test
    void nullMeetingTypeInheritsEverything() {
        channel(HOST_A, "phone");

        assertEquals(1, router.channelsFor(HOST_A, null).size());
    }
}
```

- [ ] **Step 2: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelRouterTest
```

Expected: compilation failure — `cannot find symbol: class ChannelRouter`.

- [ ] **Step 3: Write the router**

Create `src/main/java/site/asm0dey/calit/notify/ChannelRouter.java`:

```java
package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves which of a host's channels a meeting type's notifications go to.
 *
 * <pre>
 * link rows for this meetingType belonging to THIS host's channels
 *   non-empty -> exactly those channels      (override)
 *   empty     -> ALL of this host's channels (inherit)
 * </pre>
 *
 * Resolved in Java rather than SQL: a host has a handful of channels, and the per-host filter is
 * the whole point — without it one host's override would silently change a co-host's delivery.
 */
@ApplicationScoped
public class ChannelRouter {

    public List<NotificationChannel> channelsFor(Long hostOwnerId, Long meetingTypeId) {
        List<NotificationChannel> own = NotificationChannel.forOwner(hostOwnerId);
        if (own.isEmpty() || meetingTypeId == null) {
            return own;
        }
        Set<Long> ownIds = own.stream().map(c -> c.id).collect(Collectors.toSet());
        Set<Long> overridden = NotificationChannelMeetingType.linkedChannelIds(meetingTypeId).stream()
                .filter(ownIds::contains)
                .collect(Collectors.toSet());
        return overridden.isEmpty() ? own : own.stream().filter(c -> overridden.contains(c.id)).toList();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -o test -Dtest=ChannelRouterTest
```

Expected: 5 tests, 0 failures.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -o spotless:apply
git add src/main/java/site/asm0dey/calit/notify/ChannelRouter.java \
        src/test/java/site/asm0dey/calit/notify/ChannelRouterTest.java
git commit -m "feat(notify): per-meeting-type channel routing"
```

---

## Task 4: Promote the booking snapshot out of EmailService

Pure refactor, no behaviour change. `EmailService` already materializes exactly what a channel message needs, in a private `Loaded` record. Re-querying it in the notification observer would duplicate the subtle part — group bookings, per-host rows, answer rendering — across two places that must stay in step.

**Drive this task through MCP Steroid (IntelliJ), not text edits.** The native `Edit` tool and `sed` write straight to disk, leaving IntelliJ's VFS, PSI and search indices stale, so the next semantic operation (find-references, rename, inspections) answers from a stale model. Every file change in this task goes through `steroid_execute_code`, and the type-level moves use IntelliJ's own refactoring processors so references are rewritten by the IDE rather than by a regex.

**Files:**
- Create: `src/main/java/site/asm0dey/calit/email/BookingSnapshot.java` (produced by the move, not hand-written)
- Create: `src/main/java/site/asm0dey/calit/email/HostDelivery.java` (likewise)
- Create: `src/main/java/site/asm0dey/calit/email/BookingSnapshotLoader.java`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`

**Interfaces:**
- Consumes: `EmailService.AnswerLine` (stays on `EmailService` — five Qute templates declare `site.asm0dey.calit.email.EmailService$AnswerLine`, and moving it would break them for no gain).
- Produces:
  - `record BookingSnapshot(Booking booking, MeetingType meetingType, OwnerSettings owner, ZoneId zone, List<EmailService.AnswerLine> answers, List<HostDelivery> hostDeliveries)`
  - `record HostDelivery(OwnerSettings settings, Booking booking)`
  - `BookingSnapshotLoader` (`@ApplicationScoped`) with `BookingSnapshot read(Long bookingId)` (caller's transaction) and `BookingSnapshot load(Long bookingId)` (own `requiringNew` transaction). Both return `null` when the booking or its owner settings are gone.

- [ ] **Step 1: Attach to the IDE**

```
steroid_list_projects
```

Route every later call by the `project_name` this returns for the project whose `path` is the longest prefix of `/home/finkel/work_self/calit`. If no IDE is attached, `steroid_open_project` on that path first. Do not fall back to shell edits before the `steroid_*` tools have actually been tried.

- [ ] **Step 2: Rewrite the record field accesses to accessor calls — BEFORE moving anything**

This ordering is the whole trick. `l.booking` compiles today only because `Loaded` is a *private nested* record in the same top-level class; the moment it becomes a top-level record, all ~95 sites break. But a record's generated accessors (`l.booking()`) work equally well while it is still nested — so rewriting to accessors first turns the later move into a pure move with zero reference repair.

This one is genuinely textual (a record component read becoming an accessor call), so it is the one step a regex fits. Prefer IntelliJ's structural search/replace (`$l$.booking` → `$l$.booking()`, scoped to this file) if you can drive it; otherwise run the regex through `steroid_execute_code` using the read-modify-write recipe from the tool's own description (`VfsUtil.saveText`, which refreshes the VFS) — never `sed`, which writes behind the IDE's back:

```kotlin
val path = "src/main/java/site/asm0dey/calit/email/EmailService.java"
val file = project.baseDir.findFileByRelativePath(path)!!
var text = VfsUtil.loadText(file)
listOf(
    "l.booking" to "l.booking()",
    "l.meetingType" to "l.meetingType()",
    "l.owner" to "l.owner()",
    "l.zone" to "l.zone()",
    "l.answers" to "l.answers()",
    "l.hostDeliveries" to "l.hostDeliveries()",
    "hd.settings" to "hd.settings()",
    "hd.booking" to "hd.booking()",
).forEach { (from, to) ->
    text = Regex("\\b" + Regex.escape(from) + "\\b").replace(text, to)
}
WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(file, text) }
"rewrote ${'$'}{text.length} chars"
```

Every `Loaded` declaration in the file uses the variable name `l`, and every `HostDelivery` loop variable is `hd` — verified before this plan was written, so those eight patterns cover every site. The `\b` guards stop `l.bookingId`-style false matches.

- [ ] **Step 3: Verify the accessor rewrite compiles**

Use `mcp__ide__getDiagnostics` on `EmailService.java` (or `steroid_execute_code` running the IDE's own highlighting pass). Expected: no errors. A leftover `l.booking()()` or a missed site shows up here, before any structural change.

- [ ] **Step 4: Rename `Loaded` to `BookingSnapshot` with the IDE's Rename refactoring**

Through `steroid_execute_code`, resolve the nested class and run IntelliJ's rename processor so every declaration, parameter type and `new Loaded(...)` call is updated by the IDE:

```kotlin
val psi = JavaPsiFacade.getInstance(project)
    .findClass("site.asm0dey.calit.email.EmailService", GlobalSearchScope.projectScope(project))!!
val loaded = psi.findInnerClassByName("Loaded", false)!!
RenameProcessor(project, loaded, "BookingSnapshot", /* searchInComments = */ true, /* searchTextOccurrences = */ true)
    .run()
"renamed"
```

If the exact processor signature differs in this IDE build, read it back from the IDE (`steroid_execute_code` can introspect) and adjust — but do NOT substitute a text replace: the point of this step is that the IDE owns reference updating.

- [ ] **Step 5: Move `BookingSnapshot` and `HostDelivery` to top level**

Still through `steroid_execute_code`, run IntelliJ's "move inner class to upper level" processor for each nested record, targeting the existing `site.asm0dey.calit.email` package directory:

```kotlin
val emailDir = JavaPsiFacade.getInstance(project)
    .findPackage("site.asm0dey.calit.email")!!
    .getDirectories(GlobalSearchScope.projectScope(project)).first()
val outer = JavaPsiFacade.getInstance(project)
    .findClass("site.asm0dey.calit.email.EmailService", GlobalSearchScope.projectScope(project))!!
listOf("BookingSnapshot", "HostDelivery").forEach { name ->
    val inner = outer.findInnerClassByName(name, false)!!
    MoveInnerProcessor(project, inner, name, /* passOuterClass = */ false, null, emailDir).run()
}
"moved"
```

Then widen both records from package-private to `public` (they are consumed from `site.asm0dey.calit.notify` in Task 6) and give each the javadoc below, via the same `VfsUtil.saveText` recipe:

`BookingSnapshot.java`:

```java
/**
 * Immutable bundle read once in one transaction, shared by {@link EmailService} and the outbound
 * notification path. {@code hostDeliveries} is empty for a single-host booking
 * ({@code booking.groupId == null}); for a group booking it holds one entry per accepted host
 * (their own {@link OwnerSettings} + their own row of the group).
 */
public record BookingSnapshot(
        Booking booking,
        MeetingType meetingType,
        OwnerSettings owner,
        ZoneId zone,
        List<EmailService.AnswerLine> answers,
        List<HostDelivery> hostDeliveries) {}
```

`HostDelivery.java`:

```java
/** A group booking's per-host delivery target: that host's own settings + own booking row. */
public record HostDelivery(OwnerSettings settings, Booking booking) {}
```

Check diagnostics again: still zero errors, and `EmailService` now imports nothing new (same package).

- [ ] **Step 6: Create the loader**

Create `src/main/java/site/asm0dey/calit/email/BookingSnapshotLoader.java` (via `steroid_execute_code`, so the new file is registered with the VFS). The four method bodies are the ones currently in `EmailService` — `read`, `loadHostDeliveries`, `load`, `buildAnswerLines` — unchanged apart from `Loaded` having become `BookingSnapshot`:

```java
package site.asm0dey.calit.email;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Loads the {@link BookingSnapshot} every booking-triggered side effect needs. Extracted from
 * {@code EmailService} so the outbound-notification path reads the same rows through the same
 * code — group bookings, per-host rows and answer rendering are subtle enough that two copies
 * would drift.
 */
@ApplicationScoped
public class BookingSnapshotLoader {

    final MeetingHosts meetingHosts;

    @Inject
    public BookingSnapshotLoader(MeetingHosts meetingHosts) {
        this.meetingHosts = meetingHosts;
    }

    /**
     * Loads the booking + meeting type + owner settings + answers in the CALLER's active
     * transaction. Use from an already-transactional caller (the scheduler claim tx). Returns null
     * if gone. For a group booking also eagerly resolves every host's own {@link OwnerSettings} +
     * own booking row, because the per-host fan-out runs OUTSIDE this transaction.
     */
    public BookingSnapshot read(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            return null;
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        if (owner == null) {
            // No settings row means no address to send the owner copy to, so there is nothing to
            // build. Returning null gives every caller the same "nothing to send" path the
            // missing-booking case already takes, instead of an NPE on owner.timezone (calit-sv6a).
            Log.warnf("no owner_settings for owner %d -- skipping mail for booking %d", type.ownerId, booking.id);
            return null;
        }
        // coerceZone, not a bare ZoneId.of: a row written before the save-time guard existed can
        // still hold an unparseable zone, and a DateTimeException here would take out every mail
        // for that owner, not just this one (calit-4whp).
        ZoneId zone = ZoneId.of(OwnerSettings.coerceZone(owner.timezone));
        List<EmailService.AnswerLine> answers = buildAnswerLines(booking, type);
        List<HostDelivery> hostDeliveries =
                booking.groupId == null ? List.of() : loadHostDeliveries(booking.groupId, type);
        return new BookingSnapshot(booking, type, owner, zone, answers, hostDeliveries);
    }

    /** As {@link #read} but opens its own transaction — for AFTER_SUCCESS observers (no active tx). */
    public BookingSnapshot load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> read(bookingId));
    }

    /** Every accepted host's own {@code OwnerSettings} paired with their own row of this group. */
    private List<HostDelivery> loadHostDeliveries(UUID groupId, MeetingType type) {
        List<Booking> rows = Booking.group(groupId);
        List<HostDelivery> deliveries = new ArrayList<>();
        for (Long hostId : meetingHosts.hostOwnerIds(type)) {
            OwnerSettings settings = OwnerSettings.forOwner(hostId);
            if (settings == null) continue;
            Booking row = rows.stream()
                    .filter(r -> hostId.equals(r.ownerId))
                    .findFirst()
                    .orElse(null);
            if (row == null) continue;
            deliveries.add(new HostDelivery(settings, row));
        }
        return deliveries;
    }

    /**
     * Joins {@code BookingField.formFor(meetingTypeId)} (ordered by {@code position}) to
     * {@code booking.answers} by {@code fieldKey}, skipping blank/absent values. Must run inside a
     * transaction — the {@code requiringNew()} one opened by {@link #load} or the caller's own.
     */
    private static List<EmailService.AnswerLine> buildAnswerLines(Booking booking, MeetingType type) {
        List<EmailService.AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(type.ownerId, booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new EmailService.AnswerLine(field.label, value));
            }
        }
        return lines;
    }
}
```

- [ ] **Step 7: Point EmailService at the loader and delete the duplicates**

Through `steroid_execute_code`:

1. Add the field `final BookingSnapshotLoader snapshots;`, the constructor parameter `BookingSnapshotLoader snapshots,` and the assignment `this.snapshots = snapshots;`.
2. Qualify the call sites: every bare `load(` becomes `snapshots.load(` and every bare `read(` becomes `snapshots.read(` — there are 14 call sites in total (12 `load(`, 2 `read(`). The `\b` guard leaves `readAllBytes` alone.
3. Delete `read(Long)`, `load(Long)`, `loadHostDeliveries(UUID, MeetingType)` and `buildAnswerLines(Booking, MeetingType)` from `EmailService`. Prefer IntelliJ's **Safe Delete** (`SafeDeleteProcessor`) for each — it refuses if a reference still exists, which is exactly the check you want after step 2.
4. Remove the now-unused `MeetingHosts meetingHosts` field, its constructor parameter, its assignment and its import: `loadHostDeliveries` was its only user. Safe Delete will confirm that.

Keep `public record AnswerLine(String label, String value) {}` on `EmailService`.

- [ ] **Step 8: Diagnostics, then the email and scheduler suites**

`mcp__ide__getDiagnostics` on the three touched files first — zero errors — then:

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o spotless:apply
./mvnw -o test -Dtest='Email*Test,*EmailTest,Ics*Test,Outbox*Test,MultiHost*Test,Reminder*Test,PendingExpiry*Test'
```

Expected: all green, zero behaviour change.

- [ ] **Step 9: Run the full suite**

```bash
./mvnw -o test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. This is a refactor — any red here is this task's fault.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/
git commit -m "refactor(email): promote the booking snapshot out of EmailService"
```

---

## Task 5: Notification model, rendering and translations

**Files:**
- Create: `src/main/java/site/asm0dey/calit/notify/HostNotification.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelMessageRenderer.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`
- Modify: `src/main/resources/messages/msg_de.properties`, `src/main/resources/messages/msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelMessageRendererTest.java`

**Interfaces:**
- Consumes: `BookingSnapshot`, `HostDelivery` (Task 4); `AppMessageResolver.forLocale(Locale)`; `OwnerSettings.coerceZone(String)`; `AppLocales.pick(String)`.
- Produces:
  - `HostNotification` — sealed interface with `Host recipient()` and `String kind()`, plus `record Host(Long ownerId, java.util.Locale locale, java.time.ZoneId zone, String hourCycle)` and eleven implementing records: `Requested`, `Confirmed`, `Approved`, `Declined`, `Cancelled`, `Rescheduled`, `DetailsChanged`, `GuestDeclined`, `GuestRemoved`, `ReminderDue`, `ConsentRequested`.
  - `HostNotification.Host.of(OwnerSettings)` factory.
  - `ChannelMessageRenderer` (`@ApplicationScoped`) with `org.alexmond.notify4j.Message render(HostNotification n)` and `Message test(java.util.Locale locale)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelMessageRendererTest.java`:

```java
package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.email.BookingSnapshotLoader;
import site.asm0dey.calit.test.MultiHostFixtures;

/** Every kind must render a non-empty title and body in every supported locale. */
@QuarkusTest
class ChannelMessageRendererTest {

    @Inject
    ChannelMessageRenderer renderer;

    @Inject
    BookingSnapshotLoader snapshots;

    private BookingSnapshot snapshot() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "render-me", 30);
            var start = Instant.parse("2026-06-08T09:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-render";
            b.createdAt = Instant.now();
            b.persist();
            return snapshots.read(b.id);
        });
    }

    private static HostNotification.Host host(Locale locale) {
        return new HostNotification.Host(1L, locale, ZoneId.of("Europe/Berlin"), "auto");
    }

    private static BookingGuest guest() {
        var g = new BookingGuest();
        g.email = "guest@example.com";
        return g;
    }

    @Test
    void everyKindRendersInEveryLocale() {
        BookingSnapshot s = snapshot();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("he"))) {
            HostNotification.Host h = host(locale);
            Instant old = Instant.parse("2026-06-07T09:00:00Z");
            List<HostNotification> all = List.of(
                    new HostNotification.Requested(s, h),
                    new HostNotification.Confirmed(s, h),
                    new HostNotification.Approved(s, h),
                    new HostNotification.Declined(s, h),
                    new HostNotification.Cancelled(s, h, true),
                    new HostNotification.Rescheduled(s, h, old, false),
                    new HostNotification.DetailsChanged(s, h, false),
                    new HostNotification.GuestDeclined(s, h, guest()),
                    new HostNotification.GuestRemoved(s, h, guest()),
                    new HostNotification.ReminderDue(s, h),
                    new HostNotification.ConsentRequested(s.meetingType(), h, "consent-token"));
            for (HostNotification n : all) {
                var msg = renderer.render(n);
                assertNotNull(msg.title(), n.kind() + " title in " + locale);
                assertFalse(msg.title().isBlank(), n.kind() + " title in " + locale);
                assertFalse(msg.body().isBlank(), n.kind() + " body in " + locale);
            }
        }
    }

    @Test
    void kindIsTheWireStatusString() {
        BookingSnapshot s = snapshot();
        assertEquals("BOOKING_REQUESTED", new HostNotification.Requested(s, host(Locale.ENGLISH)).kind());
        assertEquals("BOOKING_CANCELLED", new HostNotification.Cancelled(s, host(Locale.ENGLISH), true).kind());
        assertEquals(
                "HOST_CONSENT_REQUESTED",
                new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "t").kind());
    }

    @Test
    void rescheduledBodyNamesBothTimes() {
        BookingSnapshot s = snapshot();
        var msg = renderer.render(new HostNotification.Rescheduled(
                s, host(Locale.ENGLISH), Instant.parse("2026-06-07T09:00:00Z"), false));
        assertTrue(msg.body().contains("→"), "rescheduled body shows old → new: " + msg.body());
    }

    @Test
    void consentBodyCarriesTheAcceptLink() {
        BookingSnapshot s = snapshot();
        var msg = renderer.render(
                new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "abc-123"));
        assertTrue(msg.body().contains("/consent/abc-123"), msg.body());
    }
}
```

- [ ] **Step 2: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelMessageRendererTest
```

Expected: compilation failure — `cannot find symbol: class HostNotification`.

- [ ] **Step 3: Write the notification model**

Create `src/main/java/site/asm0dey/calit/notify/HostNotification.java`:

```java
package site.asm0dey.calit.notify;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.i18n.AppLocales;

/**
 * One host-facing event, ready to render onto a channel. The interface guarantees only
 * {@link #recipient()} and {@link #kind()}, NOT a booking: {@link ConsentRequested} invites someone
 * to <em>become</em> a co-host and has no booking, so a common {@code booking()} accessor would
 * force a null or a fake. The exhaustive switch in {@link ChannelMessageRenderer} handles it.
 *
 * <p>{@link #kind()} does double duty: exhaustive-switch discriminator and the {@code status}
 * string on the wire, so a {@code webhook://} consumer sees {@code "BOOKING_REQUESTED"}, not prose.
 */
public sealed interface HostNotification {

    Host recipient();

    String kind();

    /** The recipient, flattened off the (detached) OwnerSettings row the sync side already read. */
    record Host(Long ownerId, Locale locale, ZoneId zone, String hourCycle) {
        public static Host of(OwnerSettings settings) {
            return new Host(
                    settings.ownerId,
                    AppLocales.pick(settings.locale),
                    ZoneId.of(OwnerSettings.coerceZone(settings.timezone)),
                    settings.timeFormat);
        }
    }

    record Requested(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_REQUESTED";
        }
    }

    record Confirmed(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_CONFIRMED";
        }
    }

    record Approved(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_APPROVED";
        }
    }

    record Declined(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_DECLINED";
        }
    }

    record Cancelled(BookingSnapshot b, Host recipient, boolean byOwner) implements HostNotification {
        public String kind() {
            return "BOOKING_CANCELLED";
        }
    }

    record Rescheduled(BookingSnapshot b, Host recipient, Instant oldStartUtc, boolean byOwner)
            implements HostNotification {
        public String kind() {
            return "BOOKING_RESCHEDULED";
        }
    }

    record DetailsChanged(BookingSnapshot b, Host recipient, boolean byOwner) implements HostNotification {
        public String kind() {
            return "BOOKING_UPDATED";
        }
    }

    record GuestDeclined(BookingSnapshot b, Host recipient, BookingGuest guest) implements HostNotification {
        public String kind() {
            return "GUEST_DECLINED";
        }
    }

    record GuestRemoved(BookingSnapshot b, Host recipient, BookingGuest guest) implements HostNotification {
        public String kind() {
            return "GUEST_REMOVED";
        }
    }

    record ReminderDue(BookingSnapshot b, Host recipient) implements HostNotification {
        public String kind() {
            return "BOOKING_REMINDER";
        }
    }

    /**
     * The one event with no booking: a pending co-host row was created and this host must accept.
     * They are notified on their inherited channel set — they cannot have overridden a type they do
     * not host yet, so the routing rule covers it with no special case.
     */
    record ConsentRequested(MeetingType meetingType, Host recipient, String consentToken)
            implements HostNotification {
        public String kind() {
            return "HOST_CONSENT_REQUESTED";
        }
    }
}
```

- [ ] **Step 4: Add the message keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, after the `// ---- Email subjects ----` block (which ends with `email_google_disconnected_subject()`), add:

```java
    // ---- Channel notifications ----
    // Titles reuse the email_*_subject keys above wherever an equivalent one exists; only the three
    // events with no email subject of their own get a title key here.

    @Message("{inviteeName} · {when}")
    String channel_body(String inviteeName, String when);

    @Message("{inviteeName} · {oldWhen} → {newWhen}")
    String channel_body_rescheduled(String inviteeName, String oldWhen, String newWhen);

    @Message("Guest declined: {meetingTypeName}")
    String channel_guest_declined_title(String meetingTypeName);

    @Message("Guest removed: {meetingTypeName}")
    String channel_guest_removed_title(String meetingTypeName);

    @Message("{guestEmail} · {when}")
    String channel_guest_body(String guestEmail, String when);

    @Message("Co-host invitation")
    String channel_consent_title();

    @Message("{meetingTypeName} — accept: {url}")
    String channel_consent_body(String meetingTypeName, String url);

    @Message("calit test notification")
    String channel_test_title();

    @Message("If you can read this, the channel works.")
    String channel_test_body();
```

Append to `src/main/resources/messages/msg_de.properties`:

```properties
channel_body={inviteeName} · {when}
channel_body_rescheduled={inviteeName} · {oldWhen} → {newWhen}
channel_guest_declined_title=Gast hat abgesagt: {meetingTypeName}
channel_guest_removed_title=Gast entfernt: {meetingTypeName}
channel_guest_body={guestEmail} · {when}
channel_consent_title=Einladung als Mitgastgeber
channel_consent_body={meetingTypeName} — annehmen: {url}
channel_test_title=calit-Testbenachrichtigung
channel_test_body=Wenn Sie das lesen können, funktioniert der Kanal.
```

Append to `src/main/resources/messages/msg_he.properties`:

```properties
channel_body={inviteeName} · {when}
channel_body_rescheduled={inviteeName} · {oldWhen} → {newWhen}
channel_guest_declined_title=אורח דחה: {meetingTypeName}
channel_guest_removed_title=אורח הוסר: {meetingTypeName}
channel_guest_body={guestEmail} · {when}
channel_consent_title=הזמנה לאירוח משותף
channel_consent_body={meetingTypeName} — לאישור: {url}
channel_test_title=התראת בדיקה של calit
channel_test_body=אם אתם רואים את ההודעה הזו, הערוץ עובד.
```

The two body keys are deliberately identical across locales: they contain no words, only a name, a timestamp and separators. Keeping them as real keys (rather than hardcoding the format) means a translator can reorder them for a right-to-left locale without touching Java.

- [ ] **Step 5: Write the renderer**

Create `src/main/java/site/asm0dey/calit/notify/ChannelMessageRenderer.java`:

```java
package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.alexmond.notify4j.Message;
import org.alexmond.notify4j.Severity;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.i18n.AppMessageResolver;

/**
 * Renders a {@link HostNotification} into a notify4j {@link Message} in the recipient's own locale,
 * timezone and hour cycle. Severity stays {@link Severity#DEFAULT} throughout: a booking is not an
 * incident, and every channel with a native priority notion keeps its own default.
 */
@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class ChannelMessageRenderer {

    final AppMessageResolver messages;

    final String baseUrl;

    @Inject
    public ChannelMessageRenderer(AppMessageResolver messages, @ConfigProperty(name = "app.base-url") String baseUrl) {
        this.messages = messages;
        this.baseUrl = baseUrl;
    }

    public Message render(HostNotification n) {
        Locale locale = n.recipient().locale();
        var m = messages.forLocale(locale);
        return switch (n) {
            case HostNotification.Requested r -> booking(r.b(), n, m.email_requested_subject(label(r.b())));
            case HostNotification.Confirmed r -> booking(r.b(), n, m.email_confirmed_subject(label(r.b())));
            case HostNotification.Approved r -> booking(r.b(), n, m.email_approved_subject(label(r.b())));
            case HostNotification.Declined r -> booking(r.b(), n, m.email_declined_subject(label(r.b())));
            case HostNotification.Cancelled r -> booking(r.b(), n, m.email_cancelled_subject(label(r.b())));
            case HostNotification.DetailsChanged r -> booking(r.b(), n, m.email_updated_subject(label(r.b())));
            case HostNotification.ReminderDue r -> booking(r.b(), n, m.email_reminder_subject(label(r.b())));
            case HostNotification.Rescheduled r -> Message.of(
                    m.email_rescheduled_subject(label(r.b())),
                    m.channel_body_rescheduled(
                            r.b().booking().inviteeName,
                            when(r.oldStartUtc(), n),
                            when(r.b().booking().startUtc, n)),
                    Severity.DEFAULT);
            case HostNotification.GuestDeclined r -> Message.of(
                    m.channel_guest_declined_title(label(r.b())),
                    m.channel_guest_body(r.guest().email, when(r.b().booking().startUtc, n)),
                    Severity.DEFAULT);
            case HostNotification.GuestRemoved r -> Message.of(
                    m.channel_guest_removed_title(label(r.b())),
                    m.channel_guest_body(r.guest().email, when(r.b().booking().startUtc, n)),
                    Severity.DEFAULT);
            case HostNotification.ConsentRequested r -> Message.of(
                    m.channel_consent_title(),
                    m.channel_consent_body(r.meetingType().name, baseUrl + "/consent/" + r.consentToken()),
                    Severity.DEFAULT);
        };
    }

    /** The "Send test" message an owner triggers from /me/settings. */
    public Message test(Locale locale) {
        var m = messages.forLocale(locale);
        return Message.of(m.channel_test_title(), m.channel_test_body(), Severity.DEFAULT);
    }

    private Message booking(BookingSnapshot b, HostNotification n, String title) {
        var m = messages.forLocale(n.recipient().locale());
        return Message.of(title, m.channel_body(b.booking().inviteeName, when(b.booking().startUtc, n)), Severity.DEFAULT);
    }

    /** The meeting label shown in every message: the booking's title override, else the type name. */
    private static String label(BookingSnapshot b) {
        return b.booking().effectiveTitle(b.meetingType());
    }

    /** Same pattern keys the emails use, so a host sees one house style across email and channels. */
    private String when(Instant instant, HostNotification n) {
        Locale locale = n.recipient().locale();
        var m = messages.forLocale(locale);
        String pattern = "h12".equals(n.recipient().hourCycle())
                ? m.email_datetime_pattern_h12()
                : m.email_datetime_pattern();
        return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(n.recipient().zone()));
    }
}
```

- [ ] **Step 6: Run the renderer test and the parity sweep**

```bash
./mvnw -o test -Dtest='ChannelMessageRendererTest,MultiHostMessageParityTest'
```

Expected: all green. `MultiHostMessageParityTest` fails if any of the nine new keys is missing from `msg_de.properties` or `msg_he.properties`, or if a property file has a key with no matching method.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -o spotless:apply
git add src/main/java/site/asm0dey/calit/notify/ src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/ src/test/java/site/asm0dey/calit/notify/
git commit -m "feat(notify): host notification model and channel message rendering"
```

---

## Task 6: Configuration, policy and the delivery path

**Files:**
- Create: `src/main/java/site/asm0dey/calit/notify/NotifyConfig.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelPolicy.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelDelivery.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelSender.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelStamp.java`
- Create: `src/main/java/site/asm0dey/calit/notify/NotificationDispatcher.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelDeliveryTest.java`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelPolicyTest.java`

**Interfaces:**
- Consumes: `ChannelRouter.channelsFor(Long, Long)` (Task 3); `ChannelMessageRenderer.render(HostNotification)` (Task 5); `BookingSnapshotLoader.load(Long)` (Task 4); the eleven `site.asm0dey.calit.booking.events.*` records (`BookingRequested(Long bookingId)`, `BookingConfirmed(Long)`, `BookingApproved(Long)`, `BookingDeclined(Long)`, `BookingCancelled(Long, boolean byOwner)`, `BookingRescheduled(Long, Instant oldStartUtc, boolean byOwner)`, `BookingDetailsChanged(Long, boolean byOwner)`, `GuestDeclined(Long bookingId, Long guestId)`, `GuestRemoved(Long, Long)`, `ReminderDue(Long)`, `HostConsentRequested(Long meetingTypeId, Long cohostOwnerId, String consentToken)`).
- Produces:
  - `NotifyConfig` with `boolean schemeAllowed(String scheme)`, `boolean allowPrivateTargets()`, `HttpClientConfig http()`.
  - `ChannelPolicy` with `enum Reason { OK, UNKNOWN_SCHEME, SCHEME_BLOCKED, PRIVATE_TARGET }`, `record Check(Reason reason, String scheme) { boolean ok() }`, `Check check(String url)`, `String redact(String url)`, `String defaultLabel(String url)`.
  - `record ChannelDelivery(Long channelId, String url, Message message)`.
  - `ChannelStamp.stamp(Long channelId, boolean ok, Instant at)` — `@Transactional @ActivateRequestContext`.

- [ ] **Step 1: Add the config properties**

Append to `src/main/resources/application.properties`, after the `calit.google.probe-interval` block:

```properties
# ---- Outbound notification channels (#194) ----
# Operator-side scheme blocklist. "*" allows every channel notify4j knows. A shared instance that
# does not want owner-supplied generic webhooks sets e.g. telegram,slack,discord,gotify,ntfy.
# Enforced at save time AND again at send time, so tightening it stops existing rows delivering
# rather than grandfathering them.
calit.notify.allowed-schemes=${NOTIFY_ALLOWED_SCHEMES:*}
# Owner-supplied URLs may name any host. Default-allow, because http://gotify.lan and
# ntfy+http://ntfy:8080 beside calit in Docker are the primary self-hosted case. A shared instance
# worried about internal probing sets this false.
calit.notify.allow-private-targets=${NOTIFY_ALLOW_PRIVATE:true}
calit.notify.max-attempts=${NOTIFY_MAX_ATTEMPTS:3}
# One attempt in tests: otherwise a single failure test sits through the full backoff ladder.
%test.calit.notify.max-attempts=1
```

- [ ] **Step 2: Write the failing policy test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelPolicyTest.java`:

```java
package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChannelPolicyTest {

    @Inject
    ChannelPolicy policy;

    @InjectSpy
    NotifyConfig config;

    @Test
    void unknownSchemeIsRejected() {
        assertEquals(ChannelPolicy.Reason.UNKNOWN_SCHEME, policy.check("carrier-pigeon://nope").reason());
        assertEquals(ChannelPolicy.Reason.UNKNOWN_SCHEME, policy.check("   ").reason());
    }

    @Test
    void starAllowsEveryKnownScheme() {
        assertTrue(policy.check("telegram://111:AAbbCC/222333").ok());
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void aBlockedSchemeIsRejectedAndTheTransportSuffixDoesNotHideIt() {
        when(config.schemeAllowed("webhook")).thenReturn(false);
        when(config.schemeAllowed("ntfy")).thenReturn(true);

        assertEquals(ChannelPolicy.Reason.SCHEME_BLOCKED, policy.check("webhook://example.com/hook").reason());
        // "ntfy+http://" must match an allowlist entry of "ntfy": tryParse reports the channel
        // scheme with the transport suffix already split off.
        assertTrue(policy.check("ntfy+http://localhost:1/topic").ok());
    }

    @Test
    void privateTargetIsRejectedOnlyWhenTheFlagIsOff() {
        assertTrue(policy.check("ntfy+http://127.0.0.1:1/topic").ok(), "default-allow");

        when(config.allowPrivateTargets()).thenReturn(false);
        assertEquals(ChannelPolicy.Reason.PRIVATE_TARGET, policy.check("ntfy+http://127.0.0.1:1/topic").reason());
    }

    @Test
    void aCredentialInTheAuthorityIsNeverResolved() {
        // telegram://<bot-token>/<chat-id>: the authority is a SECRET, not a host. Resolving it
        // would leak the bot token to a DNS server, so the private-target check must skip it.
        when(config.allowPrivateTargets()).thenReturn(false);
        assertTrue(policy.check("telegram://111:AAbbCC/222333").ok());
    }

    @Test
    void redactionHidesTheSecret() {
        String redacted = policy.redact("telegram://111:AAbbCC/222333");
        assertFalse(redacted.contains("AAbbCC"), redacted);
    }

    @Test
    void defaultLabelIsTheChannelDisplayName() {
        assertEquals("Telegram", policy.defaultLabel("telegram://111:AAbbCC/222333"));
    }
}
```

- [ ] **Step 3: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelPolicyTest
```

Expected: compilation failure — `cannot find symbol: class ChannelPolicy`.

- [ ] **Step 4: Write the config bean**

Create `src/main/java/site/asm0dey/calit/notify/NotifyConfig.java`:

```java
package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.alexmond.notify4j.HttpClientConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The {@code calit.notify.*} knobs, behind accessors so a test can spy this one bean instead of
 * restarting Quarkus under a {@code @TestProfile} per flag combination (same shape as
 * {@code CaptchaProviderConfig}).
 */
@ApplicationScoped
public class NotifyConfig {

    private static final String ALL = "*";

    final Set<String> allowed;

    final boolean allowPrivateTargets;

    final int maxAttempts;

    @Inject
    public NotifyConfig(
            @ConfigProperty(name = "calit.notify.allowed-schemes", defaultValue = ALL) String allowedSchemes,
            @ConfigProperty(name = "calit.notify.allow-private-targets", defaultValue = "true")
                    boolean allowPrivateTargets,
            @ConfigProperty(name = "calit.notify.max-attempts", defaultValue = "3") int maxAttempts) {
        this.allowed = ALL.equals(allowedSchemes.trim())
                ? Set.of()
                : Arrays.stream(allowedSchemes.split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.allowPrivateTargets = allowPrivateTargets;
        this.maxAttempts = maxAttempts;
    }

    /** An empty allowlist means "*" — every channel notify4j knows. */
    public boolean schemeAllowed(String scheme) {
        return allowed.isEmpty() || (scheme != null && allowed.contains(scheme.toLowerCase(Locale.ROOT)));
    }

    public boolean allowPrivateTargets() {
        return allowPrivateTargets;
    }

    /**
     * Built from config rather than {@code HttpClientConfig.defaults()} so {@code %test} can pin
     * max-attempts=1. Blocking retry is correct here: delivery runs on a background thread.
     */
    public HttpClientConfig http() {
        return HttpClientConfig.of(Duration.ofSeconds(10), Duration.ofSeconds(10), maxAttempts, Duration.ofSeconds(1));
    }
}
```

- [ ] **Step 5: Write the policy bean**

Create `src/main/java/site/asm0dey/calit/notify/ChannelPolicy.java`:

```java
package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import org.alexmond.notify4j.ChannelCatalog;
import org.alexmond.notify4j.FieldType;
import org.alexmond.notify4j.ParsedChannel;

/**
 * Admits or rejects a channel URL. Applied at SAVE time (so the owner gets a real error) and again
 * at SEND time (so a row saved before the allowlist was tightened stops delivering rather than
 * being grandfathered).
 */
@ApplicationScoped
public class ChannelPolicy {

    public enum Reason {
        OK,
        UNKNOWN_SCHEME,
        SCHEME_BLOCKED,
        PRIVATE_TARGET
    }

    public record Check(Reason reason, String scheme) {
        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private final ChannelCatalog catalog = ChannelCatalog.standard();

    final NotifyConfig config;

    @Inject
    public ChannelPolicy(NotifyConfig config) {
        this.config = config;
    }

    public Check check(String url) {
        Optional<ParsedChannel> parsed = catalog.tryParse(url);
        if (parsed.isEmpty()) {
            return new Check(Reason.UNKNOWN_SCHEME, null);
        }
        String scheme = parsed.get().scheme();
        if (!config.schemeAllowed(scheme)) {
            return new Check(Reason.SCHEME_BLOCKED, scheme);
        }
        if (!config.allowPrivateTargets() && hostBearing(parsed.get()) && resolvesPrivate(url)) {
            return new Check(Reason.PRIVATE_TARGET, scheme);
        }
        return new Check(Reason.OK, scheme);
    }

    /** Safe for display and logs: {@code scheme://host/…}, secrets stripped by notify4j's own redactor. */
    public String redact(String url) {
        return catalog.redact(url);
    }

    /** "Telegram", "Slack", "Gotify" … — the label a blank input falls back to at save time. */
    public String defaultLabel(String url) {
        return catalog.tryParse(url)
                .flatMap(p -> catalog.describe(p.scheme()).map(d -> d.displayName()))
                .orElseGet(() -> catalog.tryParse(url).map(ParsedChannel::scheme).orElse("Channel"));
    }

    /**
     * Whether this channel's URL authority is a real host rather than a credential. {@code
     * telegram://<bot-token>/<chat-id>} puts a SECRET where a host would go, and resolving it would
     * hand the bot token to a DNS resolver — so only channels with a URL-typed field, or one using
     * the {@code +http} cleartext transport, are ever resolved. Those are exactly the self-hosted
     * channels {@code allow-private-targets} exists for.
     */
    private boolean hostBearing(ParsedChannel parsed) {
        if (parsed.cleartextHttp()) {
            return true;
        }
        return catalog.describe(parsed.scheme())
                .map(d -> d.fields().stream().anyMatch(f -> f.type() == FieldType.URL))
                .orElse(false);
    }

    private static boolean resolvesPrivate(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false; // not a parseable authority; notify4j will fail it at send time
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress()
                        || a.isSiteLocalAddress()
                        || a.isLinkLocalAddress()
                        || a.isAnyLocalAddress()
                        || uniqueLocalIpv6(a)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false; // cannot resolve: not our business to block, the send will fail anyway
        }
        return false;
    }

    /** fc00::/7 — IPv6's private range, which {@code isSiteLocalAddress()} does not cover. */
    private static boolean uniqueLocalIpv6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }
}
```

- [ ] **Step 6: Run the policy test**

```bash
./mvnw -o test -Dtest=ChannelPolicyTest
```

Expected: 7 tests, 0 failures.

- [ ] **Step 7: Write the failing delivery test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelDeliveryTest.java`:

```java
package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * End-to-end delivery: a real booking event reaches a real HTTP endpoint through notify4j, and the
 * outcome is stamped on the channel row. The stub is a JDK {@link HttpServer} (the pattern
 * {@code CaptchaVerifierTurnstileTest} already uses — no new test dependency), and the test awaits a
 * {@link CountDownLatch} because {@code fireAsync} races the assertion.
 */
@QuarkusTest
class ChannelDeliveryTest {

    static final int PORT = 18479;
    static HttpServer server;
    static CountDownLatch hit;
    static final AtomicInteger status = new AtomicInteger(200);

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
            hit.countDown();
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @Inject
    Event<BookingConfirmed> confirmed;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        hit = new CountDownLatch(1);
        status.set(200);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    private Long channel(long ownerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = "ntfy+http://localhost:" + PORT + "/calit";
            c.label = "Stub";
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    private Long booking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "deliver-me", 30);
            var start = Instant.parse("2026-06-08T09:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-deliver-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    /** AFTER_SUCCESS observers need a committed transaction to fire from. */
    private void fireConfirmed(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> confirmed.fire(new BookingConfirmed(bookingId)));
    }

    private NotificationChannel await(Long channelId, boolean success) throws InterruptedException {
        assertTrue(hit.await(10, TimeUnit.SECONDS), "the stub never received a POST");
        for (int i = 0; i < 100; i++) { // the timestamp write happens just after the POST returns
            NotificationChannel c =
                    QuarkusTransaction.requiringNew().call(() -> NotificationChannel.findById(channelId));
            if ((success ? c.lastSuccessAt : c.lastFailureAt) != null) return c;
            Thread.sleep(50);
        }
        fail("delivery outcome was never stamped");
        return null;
    }

    @Test
    void aConfirmedBookingIsDeliveredAndStamped() throws InterruptedException {
        Long channelId = channel(1L);
        fireConfirmed(booking());

        NotificationChannel c = await(channelId, true);
        assertNotNull(c.lastSuccessAt);
        assertNull(c.lastFailureAt);
    }

    @Test
    void aFailingChannelStampsTheFailureAndNotTheSuccess() throws InterruptedException {
        status.set(500);
        Long channelId = channel(1L);
        fireConfirmed(booking());

        NotificationChannel c = await(channelId, false);
        assertNotNull(c.lastFailureAt);
        assertNull(c.lastSuccessAt);
    }

    @Test
    void anotherOwnersChannelIsNeverDelivered() throws InterruptedException {
        channel(2L); // owner 2's channel; the booking below belongs to owner 1
        fireConfirmed(booking());

        assertFalse(hit.await(2, TimeUnit.SECONDS), "owner 2's channel must not receive owner 1's booking");
    }
}
```

- [ ] **Step 8: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelDeliveryTest
```

Expected: `the stub never received a POST` — nothing dispatches yet.

- [ ] **Step 9: Write the async payload, sender and stamp**

Create `src/main/java/site/asm0dey/calit/notify/ChannelDelivery.java`:

```java
package site.asm0dey.calit.notify;

import org.alexmond.notify4j.Message;

/**
 * One async delivery: ONE channel row, its already-decrypted URL, and the message rendered for its
 * owner. One event per channel row (not per host) so a slow Telegram cannot delay a Slack, and the
 * outcome attributes to the row we already identified. The URL travels in the payload so the async
 * side needs no entity, no session and no transaction to read it.
 */
public record ChannelDelivery(Long channelId, String url, Message message) {}
```

Create `src/main/java/site/asm0dey/calit/notify/ChannelStamp.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The one DB write on the async side. A SEPARATE bean on purpose: calling a {@code @Transactional}
 * method on {@code this} from {@link ChannelSender} would bypass the interceptor entirely and the
 * write would silently never commit. {@code @ActivateRequestContext} covers the case where the
 * container gives an {@code @ObservesAsync} observer no request context (it is a no-op when one is
 * already active).
 */
@ApplicationScoped
public class ChannelStamp {

    @Transactional
    @ActivateRequestContext
    public void stamp(Long channelId, boolean ok, Instant at) {
        NotificationChannel c = NotificationChannel.findById(channelId);
        if (c == null) {
            return; // deleted between dispatch and delivery; nothing to record
        }
        if (ok) {
            c.lastSuccessAt = at;
        } else {
            c.lastFailureAt = at;
        }
        c.persist();
        Log.debugf("channel %d delivery %s", channelId, ok ? "ok" : "failed");
    }
}
```

Create `src/main/java/site/asm0dey/calit/notify/ChannelSender.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.alexmond.notify4j.Notifications;
import org.alexmond.notify4j.SendResult;

/**
 * The async half of the delivery path: one blocking HTTP POST and one timestamp write, nothing
 * else. {@code sendOnce} is stateless — no per-owner instance cache, no {@code AutoCloseable}
 * lifecycle — and gives per-row failure attribution a shared {@code Notifications} instance cannot.
 * notify4j's transition filtering and reminder machinery stay off: both hold in-memory per-replica
 * state, which cannot work coherently across calit's N stateless replicas.
 *
 * <p>The booking is already committed and emailed before any of this runs, so nothing here may
 * escape.
 */
@ApplicationScoped
public class ChannelSender {

    final NotifyConfig config;

    final ChannelPolicy policy;

    final ChannelStamp stamp;

    @Inject
    public ChannelSender(NotifyConfig config, ChannelPolicy policy, ChannelStamp stamp) {
        this.config = config;
        this.policy = policy;
        this.stamp = stamp;
    }

    void onDelivery(@ObservesAsync ChannelDelivery d) {
        Instant at = Instant.now();
        boolean ok;
        try {
            // Re-check at SEND time: a row saved before the allowlist was tightened stops
            // delivering rather than being grandfathered.
            if (!policy.check(d.url()).ok()) {
                Log.warnf("channel %d blocked by policy at send time", d.channelId());
                ok = false;
            } else {
                SendResult r = Notifications.sendOnce(List.of(d.url()), d.message(), config.http());
                ok = !r.anyFailed();
            }
        } catch (RuntimeException e) {
            // Never log d.url() — it is secret-bearing. The channel id is enough to find the row.
            Log.warnf(e, "channel %d delivery threw", d.channelId());
            ok = false;
        }
        try {
            stamp.stamp(d.channelId(), ok, at);
        } catch (RuntimeException e) {
            Log.warnf(e, "could not stamp channel %d", d.channelId());
        }
    }
}
```

- [ ] **Step 10: Write the dispatcher**

Create `src/main/java/site/asm0dey/calit/notify/NotificationDispatcher.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.BiFunction;
import org.alexmond.notify4j.Message;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.email.BookingSnapshotLoader;
import site.asm0dey.calit.email.HostDelivery;

/**
 * The synchronous half of the delivery path. Everything needing the DB or a locale happens here, on
 * the request thread, where a transaction and a request context already exist; the async side does
 * one POST and one timestamp write and never reads through Panache.
 *
 * <p>{@code @ObservesAsync} and {@code during = AFTER_SUCCESS} are mutually exclusive in CDI — an
 * async observer cannot declare a transaction phase — which forces this two-step, and the two-step
 * is what we want anyway.
 */
@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class NotificationDispatcher {

    final BookingSnapshotLoader snapshots;

    final ChannelRouter router;

    final ChannelMessageRenderer renderer;

    final ChannelPolicy policy;

    final Event<ChannelDelivery> deliveries;

    @Inject
    public NotificationDispatcher(
            BookingSnapshotLoader snapshots,
            ChannelRouter router,
            ChannelMessageRenderer renderer,
            ChannelPolicy policy,
            Event<ChannelDelivery> deliveries) {
        this.snapshots = snapshots;
        this.router = router;
        this.renderer = renderer;
        this.policy = policy;
        this.deliveries = deliveries;
    }

    // --- CDI observers: fire only after the booking transaction commits. ---

    void onRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRequested e) {
        dispatch(e.bookingId(), HostNotification.Requested::new);
    }

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        dispatch(e.bookingId(), HostNotification.Confirmed::new);
    }

    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        dispatch(e.bookingId(), HostNotification.Approved::new);
    }

    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        dispatch(e.bookingId(), HostNotification.Declined::new);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.Cancelled(s, h, e.byOwner()));
    }

    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.Rescheduled(s, h, e.oldStartUtc(), e.byOwner()));
    }

    void onDetailsChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDetailsChanged e) {
        dispatch(e.bookingId(), (s, h) -> new HostNotification.DetailsChanged(s, h, e.byOwner()));
    }

    void onGuestDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestDeclined e) {
        BookingGuest g = guest(e.guestId());
        if (g == null) return;
        dispatch(e.bookingId(), (s, h) -> new HostNotification.GuestDeclined(s, h, g));
    }

    void onGuestRemoved(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestRemoved e) {
        BookingGuest g = guest(e.guestId());
        if (g == null) return;
        dispatch(e.bookingId(), (s, h) -> new HostNotification.GuestRemoved(s, h, g));
    }

    void onReminder(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReminderDue e) {
        dispatch(e.bookingId(), HostNotification.ReminderDue::new);
    }

    /**
     * The candidate has not accepted this meeting type yet, so they are notified on their INHERITED
     * set — they cannot have overridden a type they do not host. That falls out of the routing rule
     * with no special case, because they own no link rows for it.
     */
    void onHostConsent(@Observes(during = TransactionPhase.AFTER_SUCCESS) HostConsentRequested e) {
        try {
            var loaded = QuarkusTransaction.requiringNew().call(() -> {
                MeetingType type = MeetingType.findById(e.meetingTypeId());
                OwnerSettings settings = OwnerSettings.forOwner(e.cohostOwnerId());
                return type == null || settings == null ? null : new Object[] {type, settings};
            });
            if (loaded == null) return;
            MeetingType type = (MeetingType) loaded[0];
            OwnerSettings settings = (OwnerSettings) loaded[1];
            fanOut(
                    settings,
                    type.id,
                    new HostNotification.ConsentRequested(
                            type, HostNotification.Host.of(settings), e.consentToken()));
        } catch (RuntimeException ex) {
            Log.warn("channel notification failed for host consent", ex);
        }
    }

    // --- internals ---

    private static BookingGuest guest(Long guestId) {
        return QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(guestId));
    }

    /**
     * One notification per host: a single-host booking has no {@code hostDeliveries}, so the
     * snapshot's own owner is the only recipient; a group booking fans out to each accepted host's
     * own settings.
     */
    private void dispatch(Long bookingId, BiFunction<BookingSnapshot, HostNotification.Host, HostNotification> f) {
        try {
            BookingSnapshot s = snapshots.load(bookingId);
            if (s == null) return;
            List<OwnerSettings> recipients = s.hostDeliveries().isEmpty()
                    ? List.of(s.owner())
                    : s.hostDeliveries().stream().map(HostDelivery::settings).toList();
            for (OwnerSettings settings : recipients) {
                fanOut(settings, s.meetingType().id, f.apply(s, HostNotification.Host.of(settings)));
            }
        } catch (RuntimeException e) {
            // The booking is already committed and emailed: a channel problem must never surface
            // to the caller or abort the remaining observers.
            Log.warnf(e, "channel notification failed for booking %d", bookingId);
        }
    }

    private void fanOut(OwnerSettings settings, Long meetingTypeId, HostNotification n) {
        List<NotificationChannel> channels =
                QuarkusTransaction.requiringNew().call(() -> router.channelsFor(settings.ownerId, meetingTypeId));
        if (channels.isEmpty()) {
            return; // no channel URL means no channel notifications: email only, as today
        }
        Message message = renderer.render(n);
        for (NotificationChannel c : channels) {
            // url is already decrypted by the converter during the load above, so the async side
            // needs neither the entity nor a session.
            if (!policy.check(c.url).ok()) {
                Log.warnf("channel %d skipped: blocked by policy", c.id);
                continue;
            }
            deliveries.fireAsync(new ChannelDelivery(c.id, c.url, message));
        }
    }
}
```

- [ ] **Step 11: Run the delivery test**

```bash
./mvnw -o test -Dtest=ChannelDeliveryTest
```

Expected: 3 tests, 0 failures. If the timestamp is never stamped while the POST clearly arrived, the `@Transactional`/request-context question the spec flags has materialised — check that `ChannelStamp` really is a separate bean and that `stamp` is called through the injected reference, not `this`.

- [ ] **Step 12: Run the full suite, format, commit**

```bash
./mvnw -o spotless:apply && ./mvnw -o test
git add src/main/java/site/asm0dey/calit/notify/ src/main/resources/application.properties \
        src/test/java/site/asm0dey/calit/notify/
git commit -m "feat(notify): deliver booking events to owner channels"
```

---

## Task 7: Owner UI — the channel list on /me/settings

**Files:**
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelRow.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelRejected.java`
- Create: `src/main/java/site/asm0dey/calit/notify/ChannelAdmin.java`
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (steroid — existing file)
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `src/main/resources/messages/adm_de.properties`, `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelSettingsPageTest.java`

**Interfaces:**
- Consumes: `ChannelPolicy.check/redact/defaultLabel` (Task 6); `ChannelMessageRenderer.test(Locale)` (Task 5); `NotificationChannel.forOwner/ownedBy` (Task 2); `NotifyConfig.http()` (Task 6); `CurrentOwner.id()`; `AdminResource.m()` (the request-locale `AdminMessages` accessor).
- Produces:
  - `record ChannelRow(Long id, String label, String maskedUrl, String displayName, String docsUrl, String lastSuccess, String lastFailure)` — `lastSuccess`/`lastFailure` are preformatted strings or `null`.
  - `ChannelRejected extends RuntimeException` with `ChannelPolicy.Reason reason()` and `String scheme()`.
  - `ChannelAdmin` (`@ApplicationScoped`): `List<ChannelRow> rows(Long ownerId, ZoneId zone)`, `void save(Long ownerId, List<String> ids, List<String> labels, List<String> urls)`, `void delete(Long ownerId, Long channelId)`, `boolean test(Long ownerId, Long channelId, Locale locale)`.
  - `AdminResource.Templates.settings(...)` gains three trailing parameters: `List<ChannelRow> channels, String channelError, String channelNotice`.

- [ ] **Step 1: Write the failing page test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelSettingsPageTest.java`:

```java
package site.asm0dey.calit.notify;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The channel list is plain HTML with a plain POST: no JavaScript path is required, and the page
 * always renders one empty row so a host without JS can add one channel per save.
 */
@QuarkusTest
class ChannelSettingsPageTest {

    private static final String TELEGRAM = "telegram://111:AAbbCC/222333";

    private static List<NotificationChannel> channelsOf(long ownerId) {
        return QuarkusTransaction.requiringNew().call(() -> NotificationChannel.forOwner(ownerId));
    }

    private static Long seed(long ownerId, String url, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = url;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void settingsPageAlwaysOffersAnEmptyRow() {
        given().when().get("/me/settings").then().statusCode(200).body(containsString("name=\"channelUrl\""));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void savingAChannelStoresItAndRendersItRedacted() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", TELEGRAM)
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200)
                .body(not(containsString("AAbbCC")));

        var saved = channelsOf(1L);
        assertEquals(1, saved.size());
        assertEquals(TELEGRAM, saved.getFirst().url, "the real URL is stored");
        assertEquals("Telegram", saved.getFirst().label, "a blank label defaults to the display name");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void resubmittingTheRedactedValueKeepsTheStoredSecret() {
        Long id = seed(1L, TELEGRAM, "Phone");
        String redacted = given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
                .lines()
                .filter(l -> l.contains("name=\"channelUrl\"") && l.contains("value=\"telegram"))
                .findFirst()
                .orElseThrow()
                .replaceAll(".*value=\"([^\"]+)\".*", "$1");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", String.valueOf(id))
                .formParam("channelLabel", "Phone")
                .formParam("channelUrl", redacted)
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        assertEquals(TELEGRAM, channelsOf(1L).getFirst().url, "an untouched redacted value must not overwrite");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void anUnsupportedUrlIsRejectedAndNothingIsStored() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", "carrier-pigeon://nope")
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200)
                .body(containsString("supported"));

        assertTrue(channelsOf(1L).isEmpty());
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void deleteRemovesOnlyYourOwnChannel() {
        Long mine = seed(1L, TELEGRAM, "Mine");
        Long theirs = seed(2L, "slack://T00/B00/xxxx", "Theirs");

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/settings/channels/" + theirs + "/delete")
                .then()
                .statusCode(200);
        assertEquals(1, channelsOf(2L).size(), "another owner's channel must survive");

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/settings/channels/" + mine + "/delete")
                .then()
                .statusCode(200);
        assertTrue(channelsOf(1L).isEmpty());
    }
}
```

- [ ] **Step 2: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelSettingsPageTest
```

Expected: failures — `/me/settings` has no `channelUrl` input and `/me/settings/channels` 404s.

- [ ] **Step 3: Write the view model and the rejection signal**

Create `src/main/java/site/asm0dey/calit/notify/ChannelRow.java`:

```java
package site.asm0dey.calit.notify;

/**
 * One rendered row of the channel list. {@code maskedUrl} is the redacted form — the real URL never
 * reaches the browser. {@code lastSuccess}/{@code lastFailure} are preformatted in the owner's zone,
 * or null when that has never happened.
 */
public record ChannelRow(
        Long id,
        String label,
        String maskedUrl,
        String displayName,
        String docsUrl,
        String lastSuccess,
        String lastFailure) {}
```

Create `src/main/java/site/asm0dey/calit/notify/ChannelRejected.java`:

```java
package site.asm0dey.calit.notify;

/** A channel URL the policy refuses at save time. Carries enough to build a localized message. */
public class ChannelRejected extends RuntimeException {

    private final transient ChannelPolicy.Reason reason;

    private final String scheme;

    public ChannelRejected(ChannelPolicy.Reason reason, String scheme) {
        super("channel rejected: " + reason);
        this.reason = reason;
        this.scheme = scheme;
    }

    public ChannelPolicy.Reason reason() {
        return reason;
    }

    public String scheme() {
        return scheme;
    }
}
```

- [ ] **Step 4: Write the admin service**

Create `src/main/java/site/asm0dey/calit/notify/ChannelAdmin.java`:

```java
package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.alexmond.notify4j.ChannelCatalog;
import org.alexmond.notify4j.Notifications;
import org.alexmond.notify4j.SendResult;

/** Everything the /me/settings channel handlers do, kept out of the already-large AdminResource. */
@ApplicationScoped
public class ChannelAdmin {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int LABEL_MAX = 64;

    private final ChannelCatalog catalog = ChannelCatalog.standard();

    final ChannelPolicy policy;

    final ChannelMessageRenderer renderer;

    final NotifyConfig config;

    @Inject
    public ChannelAdmin(ChannelPolicy policy, ChannelMessageRenderer renderer, NotifyConfig config) {
        this.policy = policy;
        this.renderer = renderer;
        this.config = config;
    }

    public List<ChannelRow> rows(Long ownerId, ZoneId zone) {
        List<ChannelRow> rows = new ArrayList<>();
        for (NotificationChannel c : NotificationChannel.forOwner(ownerId)) {
            var descriptor = catalog.tryParse(c.url).flatMap(p -> catalog.describe(p.scheme()));
            rows.add(new ChannelRow(
                    c.id,
                    c.label,
                    policy.redact(c.url),
                    descriptor.map(d -> d.displayName()).orElse(""),
                    descriptor.map(d -> d.docsUrl()).orElse(null),
                    stamp(c.lastSuccessAt, zone),
                    stamp(c.lastFailureAt, zone)));
        }
        return rows;
    }

    /**
     * Repeatable inputs: the three lists are index-aligned because every rendered row emits all
     * three fields (a new row emits an empty {@code channelId}). A row with a blank URL is ignored —
     * removing a channel is the Delete button, not an emptied field.
     */
    @Transactional
    public void save(Long ownerId, List<String> ids, List<String> labels, List<String> urls) {
        for (int i = 0; i < urls.size(); i++) {
            String submitted = value(urls, i).trim();
            if (submitted.isEmpty()) {
                continue;
            }
            NotificationChannel existing = parseId(value(ids, i))
                    .map(id -> NotificationChannel.ownedBy(id, ownerId))
                    .orElse(null);
            // An unchanged redacted value means "keep the stored secret": the real URL never
            // round-trips through the browser, so it cannot come back from the form.
            String url = existing != null && submitted.equals(policy.redact(existing.url)) ? existing.url : submitted;
            var check = policy.check(url);
            if (!check.ok()) {
                throw new ChannelRejected(check.reason(), check.scheme());
            }
            NotificationChannel row = existing;
            if (row == null) {
                row = new NotificationChannel();
                row.ownerId = ownerId;
                row.createdAt = Instant.now();
            }
            row.url = url;
            String label = value(labels, i).trim();
            row.label = label.isEmpty() ? policy.defaultLabel(url) : label;
            if (row.label.length() > LABEL_MAX) {
                row.label = row.label.substring(0, LABEL_MAX);
            }
            row.persist();
        }
    }

    @Transactional
    public void delete(Long ownerId, Long channelId) {
        NotificationChannel c = NotificationChannel.ownedBy(channelId, ownerId);
        if (c != null) {
            c.delete(); // link rows cascade in the DB (ON DELETE CASCADE)
        }
    }

    /**
     * Sends a test message inline and reports the outcome immediately. This is the only way a host
     * learns a URL is wrong BEFORE a real booking — {@code last_failure_at} is by definition after
     * the fact. Runs on the request thread on purpose: the owner is waiting for the answer.
     */
    public boolean test(Long ownerId, Long channelId, Locale locale) {
        NotificationChannel c = NotificationChannel.ownedBy(channelId, ownerId);
        if (c == null || !policy.check(c.url).ok()) {
            return false;
        }
        try {
            SendResult r = Notifications.sendOnce(List.of(c.url), renderer.test(locale), config.http());
            return !r.anyFailed();
        } catch (RuntimeException e) {
            Log.warnf(e, "test delivery to channel %d threw", channelId);
            return false;
        }
    }

    private static String value(List<String> list, int i) {
        return list != null && i < list.size() && list.get(i) != null ? list.get(i) : "";
    }

    private static java.util.Optional<Long> parseId(String raw) {
        try {
            return raw == null || raw.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(Long.valueOf(raw.trim()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static String stamp(Instant at, ZoneId zone) {
        return at == null ? null : STAMP.format(at.atZone(zone));
    }
}
```

- [ ] **Step 5: Add the admin strings**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, next to the other `adm_settings_*` keys:

```java
    // ---- Notification channels ----

    @Message("Notification channels")
    String adm_settings_channels_h2();

    @Message("Label")
    String adm_settings_channels_label();

    @Message("Channel URL")
    String adm_settings_channels_url();

    @Message("Add another")
    String adm_settings_channels_add();

    @Message("Save channels")
    String adm_settings_channels_save();

    @Message("Send test")
    String adm_settings_channels_test();

    @Message("Delete")
    String adm_settings_channels_delete();

    @Message("Docs")
    String adm_settings_channels_docs();

    @Message("No channels yet — bookings reach you by email only.")
    String adm_settings_channels_none();

    @Message("Last delivery OK")
    String adm_settings_channels_last_ok();

    @Message("Last delivery failed")
    String adm_settings_channels_last_failed();

    @Message("Channels saved")
    String adm_settings_channels_saved();

    @Message("Test delivered")
    String adm_settings_channels_test_ok();

    @Message("Test failed to deliver")
    String adm_settings_channels_test_failed();

    @Message("That is not a supported channel URL")
    String adm_settings_channels_invalid();

    @Message("Channel type {scheme} is not allowed on this server")
    String adm_settings_channels_scheme_blocked(String scheme);

    @Message("Private network targets are not allowed on this server")
    String adm_settings_channels_private_blocked();
```

Append to `src/main/resources/messages/adm_de.properties`:

```properties
adm_settings_channels_h2=Benachrichtigungskanäle
adm_settings_channels_label=Bezeichnung
adm_settings_channels_url=Kanal-URL
adm_settings_channels_add=Weiteren hinzufügen
adm_settings_channels_save=Kanäle speichern
adm_settings_channels_test=Test senden
adm_settings_channels_delete=Löschen
adm_settings_channels_docs=Doku
adm_settings_channels_none=Noch keine Kanäle — Buchungen erreichen Sie nur per E-Mail.
adm_settings_channels_last_ok=Letzte Zustellung OK
adm_settings_channels_last_failed=Letzte Zustellung fehlgeschlagen
adm_settings_channels_saved=Kanäle gespeichert
adm_settings_channels_test_ok=Test zugestellt
adm_settings_channels_test_failed=Test konnte nicht zugestellt werden
adm_settings_channels_invalid=Das ist keine unterstützte Kanal-URL
adm_settings_channels_scheme_blocked=Kanaltyp {scheme} ist auf diesem Server nicht erlaubt
adm_settings_channels_private_blocked=Ziele im privaten Netzwerk sind auf diesem Server nicht erlaubt
```

Append to `src/main/resources/messages/adm_he.properties`:

```properties
adm_settings_channels_h2=ערוצי התראות
adm_settings_channels_label=תווית
adm_settings_channels_url=כתובת הערוץ
adm_settings_channels_add=הוספת ערוץ נוסף
adm_settings_channels_save=שמירת ערוצים
adm_settings_channels_test=שליחת בדיקה
adm_settings_channels_delete=מחיקה
adm_settings_channels_docs=תיעוד
adm_settings_channels_none=אין עדיין ערוצים — הזמנות יגיעו אליך בדוא"ל בלבד.
adm_settings_channels_last_ok=המסירה האחרונה הצליחה
adm_settings_channels_last_failed=המסירה האחרונה נכשלה
adm_settings_channels_saved=הערוצים נשמרו
adm_settings_channels_test_ok=הבדיקה נמסרה
adm_settings_channels_test_failed=הבדיקה לא נמסרה
adm_settings_channels_invalid=זו אינה כתובת ערוץ נתמכת
adm_settings_channels_scheme_blocked=סוג הערוץ {scheme} אינו מותר בשרת זה
adm_settings_channels_private_blocked=יעדים ברשת פרטית אינם מותרים בשרת זה
```

- [ ] **Step 6: Wire the handlers into AdminResource (via steroid)**

Extend the `Templates.settings` native method with three trailing parameters and update both existing call sites (`settings()` at ~line 1418 and `updateSettings()` at ~line 1464) to pass `channelRows(), null, null`:

```java
        public static native TemplateInstance settings(
                OwnerSettings settings,
                int reminderLeadMinutes,
                Long pendingCount,
                List<String> zones,
                boolean isAdmin,
                String title,
                List<ChannelRow> channels,
                String channelError,
                String channelNotice);
```

Add the injected `ChannelAdmin channelAdmin;` field (constructor injection, matching the class's existing style) and:

```java
    /** This owner's channel rows, timestamps formatted in their own timezone. */
    private List<ChannelRow> channelRows() {
        return channelAdmin.rows(currentOwner.id(), zoneOfCurrentOwner());
    }

    /** Re-render /me/settings with an optional channel error/notice. */
    private TemplateInstance settingsInstance(String channelError, String channelNotice) {
        return Templates.settings(
                OwnerSettings.forOwner(currentOwner.id()),
                reminderLeadMinutes,
                pendingCount(),
                OwnerSettings.zoneIds(),
                isAdmin(),
                m().adm_settings_title(),
                channelRows(),
                channelError,
                channelNotice);
    }

    @POST
    @Path("/settings/channels")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance saveChannels(MultivaluedMap<String, String> form) {
        try {
            channelAdmin.save(
                    currentOwner.id(),
                    form.getOrDefault("channelId", List.of()),
                    form.getOrDefault("channelLabel", List.of()),
                    form.getOrDefault("channelUrl", List.of()));
        } catch (ChannelRejected e) {
            return settingsInstance(channelErrorMessage(e), null);
        }
        return settingsInstance(null, m().adm_settings_channels_saved());
    }

    @POST
    @Path("/settings/channels/{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance deleteChannel(@PathParam("id") Long id) {
        channelAdmin.delete(currentOwner.id(), id);
        return settingsInstance(null, null);
    }

    @POST
    @Path("/settings/channels/{id}/test")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance testChannel(@PathParam("id") Long id) {
        boolean ok = channelAdmin.test(currentOwner.id(), id, activeLocale.get());
        return ok
                ? settingsInstance(null, m().adm_settings_channels_test_ok())
                : settingsInstance(m().adm_settings_channels_test_failed(), null);
    }

    private String channelErrorMessage(ChannelRejected e) {
        return switch (e.reason()) {
            case SCHEME_BLOCKED -> m().adm_settings_channels_scheme_blocked(e.scheme());
            case PRIVATE_TARGET -> m().adm_settings_channels_private_blocked();
            default -> m().adm_settings_channels_invalid();
        };
    }
```

`zoneOfCurrentOwner()` is the existing helper around line 297 that returns `"UTC"` when settings are unset — wrap it with `ZoneId.of(...)`, or add a small private `ZoneId` variant beside it if it returns a `String`.

`settings()` and `updateSettings()` keep their own bodies; the simplest change is to have `settings()` delegate to `settingsInstance(null, null)` and to append `channelRows(), null, null` to `updateSettings()`'s existing `Templates.settings(...)` call.

- [ ] **Step 7: Extend the template**

Append to `src/main/resources/templates/AdminResource/settings.html`, before the closing `{/include}` (and add the three new parameter declarations at the top of the file):

```html
{@java.util.List<site.asm0dey.calit.notify.ChannelRow> channels}
{@java.lang.String channelError}
{@java.lang.String channelNotice}
```

```html
  <h2 class="text-xl font-bold mt-8 mb-2">{adm:adm_settings_channels_h2}</h2>
  {#if channelError}
  <div class="alert alert-error mb-2 max-w-2xl"><span>{channelError}</span></div>
  {/if}
  {#if channelNotice}
  <div class="alert alert-info mb-2 max-w-2xl"><span>{channelNotice}</span></div>
  {/if}
  {#if channels.isEmpty}
  <p class="text-sm text-base-content/70 mb-2">{adm:adm_settings_channels_none}</p>
  {/if}
  <form method="post" action="/me/settings/channels" class="fieldset bg-base-100 border border-base-300 rounded-box p-4 max-w-2xl" id="channel-form">
    <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
    <div id="channel-rows">
      {#for c in channels}
      <div class="flex flex-wrap gap-2 items-center mb-2">
        <input type="hidden" name="channelId" value="{c.id}">
        <input class="input input-sm" type="text" name="channelLabel" value="{c.label}" aria-label="{adm:adm_settings_channels_label}">
        <input class="input input-sm grow" type="text" name="channelUrl" value="{c.maskedUrl}" aria-label="{adm:adm_settings_channels_url}">
        {#if c.docsUrl}<a class="link link-hover text-xs" href="{c.docsUrl}" rel="noopener noreferrer" target="_blank">{adm:adm_settings_channels_docs}</a>{/if}
        {#if c.lastSuccess}<span class="badge badge-success badge-sm">{adm:adm_settings_channels_last_ok} {c.lastSuccess}</span>{/if}
        {#if c.lastFailure}<span class="badge badge-error badge-sm">{adm:adm_settings_channels_last_failed} {c.lastFailure}</span>{/if}
      </div>
      {/for}
      <div class="flex flex-wrap gap-2 items-center mb-2">
        <input type="hidden" name="channelId" value="">
        <input class="input input-sm" type="text" name="channelLabel" value="" aria-label="{adm:adm_settings_channels_label}">
        <input class="input input-sm grow" type="text" name="channelUrl" value="" aria-label="{adm:adm_settings_channels_url}">
      </div>
    </div>
    <button type="submit" class="btn btn-primary btn-sm mt-2">{adm:adm_settings_channels_save}</button>
  </form>

  {#for c in channels}
  <div class="flex gap-2 mt-1 max-w-2xl">
    <form method="post" action="/me/settings/channels/{c.id}/test">
      <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
      <button type="submit" class="btn btn-ghost btn-xs">{adm:adm_settings_channels_test} — {c.label}</button>
    </form>
    <form method="post" action="/me/settings/channels/{c.id}/delete">
      <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
      <button type="submit" class="btn btn-ghost btn-xs text-error">{adm:adm_settings_channels_delete}</button>
    </form>
  </div>
  {/for}
```

**No-JS behaviour:** the page always renders existing rows plus one empty row, so a host adds one channel per save and the re-render gives a fresh empty row. The optional `+ Add another` enhancement (a small inline script that clones the empty row so several go in at once) posts the exact same form to the exact same endpoint; add it only after the no-JS path is green, and note that RestAssured cannot execute it, so only the no-JS path is tested.

Per-scheme generated forms are deliberately **not** in scope: dynamic fields per channel type need either JS or a two-step form. One URL input validated by `tryParse` plus the allowlist gives good errors with one field and a plain POST. Catalog-driven forms are a clean later enhancement.

- [ ] **Step 8: Run the page test and the parity sweep**

```bash
./mvnw -o test -Dtest='ChannelSettingsPageTest,MultiHostMessageParityTest'
```

Expected: all green.

- [ ] **Step 9: Format and commit**

```bash
./mvnw -o spotless:apply
git add src/main/java/site/asm0dey/calit/notify/ src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java src/main/resources/messages/ \
        src/main/resources/templates/AdminResource/settings.html src/test/java/site/asm0dey/calit/notify/
git commit -m "feat(notify): manage notification channels from /me/settings"
```

---

## Task 8: Per-meeting-type routing override

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (creator's own type — steroid)
- Modify: `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` (co-host's view of a shared type — steroid)
- Modify: `src/main/resources/templates/AdminResource/meetingTypeDetail.html`
- Modify: `src/main/resources/templates/SharedMeetingsResource/sharedAvailability.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, `adm_de.properties`, `adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/notify/ChannelOverrideTest.java`

**Interfaces:**
- Consumes: `ChannelAdmin.rows(Long, ZoneId)`, `ChannelRow` (Task 7); `NotificationChannelMeetingType.linkedChannelIds/replaceLinks` (Task 2); `ChannelRouter.channelsFor` (Task 3); `AdminResource.requireType(Long)`; `SharedMeetingsResource.requireAcceptedHost(Long)`.
- Produces: `POST /me/meeting-types/{id}/notifications` and `POST /me/shared/{typeId}/notifications`, both taking `@RestForm String mode` (`all` | `custom`) and repeated `@RestForm List<Long> channelIds`. Both templates gain `List<ChannelRow> channels` and `Set<Long> selectedChannelIds`.

Both routes must resolve the acting host the way their surrounding resource already does — `AdminResource.requireType` for a creator, `SharedMeetingsResource.requireAcceptedHost` for a co-host — so one host's override can never write another host's links.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/notify/ChannelOverrideTest.java`:

```java
package site.asm0dey.calit.notify;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The override is per (host, meeting type): host 1 narrowing a co-hosted type must not change what
 * host 2 receives. Exercised through the HTTP layer, because the per-host scoping lives in the
 * handler's choice of "which channels are mine".
 */
@QuarkusTest
class ChannelOverrideTest {

    @Inject
    ChannelRouter router;

    private Long channel(long ownerId, String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = "ntfy+http://localhost:1/" + label;
            c.label = label;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    private MeetingType sharedType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(1L, "Creator");
            MultiHostFixtures.settings(2L, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(1L, 2L, "override-me", 30, false);
        });
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void customModeNarrowsOnlyTheActingHost() {
        Long a1 = channel(1L, "a-phone");
        channel(1L, "a-slack");
        channel(2L, "b-phone");
        channel(2L, "b-slack");
        MeetingType type = sharedType();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "custom")
                .formParam("channelIds", String.valueOf(a1))
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200);

        assertEquals(1, router.channelsFor(1L, type.id).size(), "creator narrowed");
        assertEquals(2, router.channelsFor(2L, type.id).size(), "co-host still inherits");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void allModeClearsTheOverride() {
        Long a1 = channel(1L, "a-phone");
        channel(1L, "a-slack");
        MeetingType type = sharedType();
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, java.util.List.of(a1), java.util.List.of(a1)));
        assertEquals(1, router.channelsFor(1L, type.id).size());

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "all")
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200);

        assertEquals(2, router.channelsFor(1L, type.id).size(), "back to inheriting everything");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void customWithNoChannelIsRejected() {
        Long a1 = channel(1L, "a-phone");
        MeetingType type = sharedType();
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, java.util.List.of(a1), java.util.List.of(a1)));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "custom")
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("at least one"));

        assertEquals(1, router.channelsFor(1L, type.id).size(), "the previous override survives a rejected save");
    }
}
```

- [ ] **Step 2: Run it to watch it fail**

```bash
./mvnw -o test -Dtest=ChannelOverrideTest
```

Expected: 404 on `/me/meeting-types/{id}/notifications`.

- [ ] **Step 3: Add the admin strings**

In `AdminMessages.java`:

```java
    @Message("Notifications")
    String adm_detail_notifications_h2();

    @Message("All my channels")
    String adm_detail_notifications_all();

    @Message("Custom for this meeting type")
    String adm_detail_notifications_custom();

    @Message("Pick at least one channel, or choose \"All my channels\".")
    String adm_detail_notifications_need_one();

    @Message("Notification routing saved")
    String adm_detail_notifications_saved();
```

`adm_de.properties`:

```properties
adm_detail_notifications_h2=Benachrichtigungen
adm_detail_notifications_all=Alle meine Kanäle
adm_detail_notifications_custom=Eigene Auswahl für diesen Termintyp
adm_detail_notifications_need_one=Wählen Sie mindestens einen Kanal oder „Alle meine Kanäle“.
adm_detail_notifications_saved=Benachrichtigungs-Routing gespeichert
```

`adm_he.properties`:

```properties
adm_detail_notifications_h2=התראות
adm_detail_notifications_all=כל הערוצים שלי
adm_detail_notifications_custom=מותאם לסוג פגישה זה
adm_detail_notifications_need_one=בחרו לפחות ערוץ אחד, או בחרו ״כל הערוצים שלי״.
adm_detail_notifications_saved=ניתוב ההתראות נשמר
```

- [ ] **Step 4: Add the creator-side handler (AdminResource, via steroid)**

Extend `Templates.meetingTypeDetail` with two trailing parameters — `List<ChannelRow> channels, Set<Long> selectedChannelIds` — and pass them from `detailInstance(Long, String, String)`:

```java
        List<ChannelRow> channels = channelAdmin.rows(currentOwner.id(), zoneOfCurrentOwner());
        Set<Long> ownIds = channels.stream().map(ChannelRow::id).collect(Collectors.toSet());
        Set<Long> selected = NotificationChannelMeetingType.linkedChannelIds(id).stream()
                .filter(ownIds::contains)
                .collect(Collectors.toSet());
```

Add the handler:

```java
    @POST
    @Path("/meeting-types/{id}/notifications")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveNotificationRouting(
            @PathParam("id") Long id, @RestForm String mode, @RestForm List<Long> channelIds) {
        requireType(id); // 404 unless this type belongs to the current owner
        List<Long> ownIds = NotificationChannel.forOwner(currentOwner.id()).stream()
                .map(c -> c.id)
                .toList();
        List<Long> keep = "custom".equals(mode)
                ? channelIds.stream().filter(ownIds::contains).toList()
                : List.of();
        if ("custom".equals(mode) && keep.isEmpty()) {
            // "No link rows" already means INHERIT, so an empty custom selection is not expressible
            // as a per-type mute — reject it rather than silently turning it into "all".
            return detailInstance(id, m().adm_detail_notifications_need_one());
        }
        NotificationChannelMeetingType.replaceLinks(id, ownIds, keep);
        return detailInstance(id, null, m().adm_detail_notifications_saved());
    }
```

- [ ] **Step 5: Add the co-host-side handler (SharedMeetingsResource, via steroid)**

Same shape, with the co-host guard and the shared template. Extend `Templates.sharedAvailability` with `List<ChannelRow> channels, Set<Long> selectedChannelIds`, populate them in `availabilityInstance` exactly as above, and add:

```java
    @POST
    @Path("/shared/{typeId}/notifications")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance saveNotificationRouting(
            @PathParam("typeId") Long typeId, @RestForm String mode, @RestForm List<Long> channelIds) {
        requireAcceptedHost(typeId); // 404/403 unless the current owner really co-hosts this type
        List<Long> ownIds = NotificationChannel.forOwner(currentOwner.id()).stream()
                .map(c -> c.id)
                .toList();
        List<Long> keep = "custom".equals(mode)
                ? channelIds.stream().filter(ownIds::contains).toList()
                : List.of();
        if ("custom".equals(mode) && keep.isEmpty()) {
            return availabilityInstance(typeId, m().adm_detail_notifications_need_one());
        }
        NotificationChannelMeetingType.replaceLinks(typeId, ownIds, keep);
        return availabilityInstance(typeId, null, m().adm_detail_notifications_saved());
    }
```

`replaceLinks` only ever deletes links naming a channel in `ownIds`, so a co-host saving their own routing cannot disturb the creator's links on the same type — that is the per-host half of the routing rule, enforced at the write as well as the read.

- [ ] **Step 6: Add the block to both templates**

Add the parameter declarations at the top of each template:

```html
{@java.util.List<site.asm0dey.calit.notify.ChannelRow> channels}
{@java.util.Set<java.lang.Long> selectedChannelIds}
```

and the block itself (identical in both, only the `action` differs — `/me/meeting-types/{type.id}/notifications` on the detail page, `/me/shared/{type.id}/notifications` on the shared page):

```html
  <h2 class="text-xl font-bold mt-8 mb-2">{adm:adm_detail_notifications_h2}</h2>
  {#if channels.isEmpty}
  <p class="text-sm text-base-content/70">{adm:adm_settings_channels_none}</p>
  {#else}
  <form method="post" action="/me/meeting-types/{type.id}/notifications" class="fieldset bg-base-100 border border-base-300 rounded-box p-4 max-w-2xl">
    <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
    <label class="label cursor-pointer justify-start gap-2">
      <input type="radio" name="mode" value="all" class="radio radio-sm"{#if selectedChannelIds.isEmpty} checked{/if}>
      {adm:adm_detail_notifications_all}
      <span class="text-xs text-base-content/70">({#for c in channels}{c.label}{#if c_hasNext}, {/if}{/for})</span>
    </label>
    <label class="label cursor-pointer justify-start gap-2">
      <input type="radio" name="mode" value="custom" class="radio radio-sm"{#if !selectedChannelIds.isEmpty} checked{/if}>
      {adm:adm_detail_notifications_custom}
    </label>
    <div class="pl-6">
      {#for c in channels}
      <label class="label cursor-pointer justify-start gap-2">
        <input type="checkbox" name="channelIds" value="{c.id}" class="checkbox checkbox-sm"{#if selectedChannelIds.contains(c.id)} checked{/if}>
        {c.label}
      </label>
      {/for}
    </div>
    <button type="submit" class="btn btn-primary btn-sm mt-2">{adm:adm_settings_channels_save}</button>
  </form>
  {/if}
```

Radio plus checkboxes, so it degrades without JS: an untouched type reads "All my channels (…)" rather than looking empty.

- [ ] **Step 7: Run the override test plus the two page suites**

```bash
./mvnw -o test -Dtest='ChannelOverrideTest,ChannelSettingsPageTest,MultiHostMessageParityTest'
```

Expected: all green.

- [ ] **Step 8: Format and commit**

```bash
./mvnw -o spotless:apply
git add src/main/java/site/asm0dey/calit/web/ src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/ src/main/resources/templates/ src/test/java/site/asm0dey/calit/notify/
git commit -m "feat(notify): per-meeting-type channel routing override"
```

---

## Task 9: Documentation, configuration reference and changelog

Docs are part of "done", not follow-up.

**Files:**
- Modify: `.env.example` (on `outbound-notifications`)
- Modify (on the **`docs-site`** branch): `docs-site/src/content/docs/releases/changelog.md`, the configuration reference page, and a new usage page

- [ ] **Step 1: Add the three variables to `.env.example`**

Append, after the CAPTCHA block:

```bash
# --- Outbound notification channels (optional) ---
# Owners register their own channel URLs (Telegram, Slack, Discord, ntfy, Gotify, webhook, …) under
# /me/settings; these knobs are the operator's side of that.
# Comma-separated scheme allowlist, or * for every channel notify4j supports. A shared instance that
# does not want owner-supplied generic webhooks sets e.g. telegram,slack,discord,gotify,ntfy.
NOTIFY_ALLOWED_SCHEMES=*
# Whether an owner may point a channel at a private/loopback address. Default true: http://gotify.lan
# and ntfy+http://ntfy:8080 beside calit in Docker are the primary self-hosted case. Set false on a
# shared instance to stop an authenticated user probing the internal network.
NOTIFY_ALLOW_PRIVATE=true
# Delivery attempts per channel before giving up (exponential backoff, capped at 30s).
NOTIFY_MAX_ATTEMPTS=3
```

- [ ] **Step 2: Verify the run-time default behaviour matches the docs you are about to write**

```bash
export JAVA_HOME=/home/finkel/.sdkman/candidates/java/26.0.1-librca
./mvnw -o test -Dtest='ChannelPolicyTest,ChannelDeliveryTest'
```

Expected: green. Do not document behaviour a test has not shown.

- [ ] **Step 3: Write the docs on the `docs-site` branch**

```bash
git switch docs-site
```

Add to the configuration reference page the three `NOTIFY_*` variables with the same wording as `.env.example`.

Add a usage page covering, per provider, how an owner obtains a channel URL — at minimum Telegram (BotFather token + chat id), Slack (incoming webhook), Discord (channel webhook), ntfy (topic URL, self-hosted included) and Gotify (app token) — plus:

- the `/me/settings` flow: paste the URL, save, press **Send test**;
- the per-meeting-type override on a meeting type's page;
- that deleting the row is how you turn a channel off (there is no pause switch);
- that channels deliver even when the owner's routine-email flag is off, because that flag governs owner *email*;
- that the UI can show *that* a delivery failed and when, but not *why* — notify4j reports counts only, with no status code or exception, so there is no honest error to store.

- [ ] **Step 4: Add the changelog entry under `## Unreleased`**

In `docs-site/src/content/docs/releases/changelog.md`, create the `## Unreleased` section if absent (standing subtitle: "Merged but not yet in a tagged release.") and add two terse bullets — one statement of what the software now does each, ~20-35 words, no bold lead-in, no before/after narrative:

```markdown
- Owners can register notification channel URLs (Telegram, Slack, Discord, ntfy, Gotify, webhook and
  more) under `/me/settings` and receive every booking event on them. Migration `V32` adds
  `notification_channel`; URLs are encrypted at rest. ([#N](https://github.com/asm0dey/calit/pull/N))
- Channel delivery is tuned with `NOTIFY_ALLOWED_SCHEMES`, `NOTIFY_ALLOW_PRIVATE` and
  `NOTIFY_MAX_ATTEMPTS`. A meeting type can override which channels it uses, per host; a failed
  delivery shows when it failed, not why. ([#N](https://github.com/asm0dey/calit/pull/N))
```

Close the section with its upgrade note: `Upgrade: nothing to do — `TOKEN_ENCRYPTION_KEY` is already required, and no channel exists until an owner adds one.`

- [ ] **Step 5: Commit both branches**

```bash
git add docs-site/src/content/docs/
git commit -m "docs: outbound notification channels"
git switch outbound-notifications
git add .env.example
git commit -m "docs: NOTIFY_* configuration in .env.example"
```

- [ ] **Step 6: Close out the bean**

```bash
./mvnw -o test   # whole suite, green, before the branch becomes a PR
beans update calit-6rzr -s completed --body-append "## Summary of Changes

Per-owner notification channels delivered via notify4j-core: migration V32 (notification_channel +
notification_channel_meeting_type), encrypted URLs, per-host per-meeting-type routing, 11 booking
events, /me/settings management UI with an inline send-test, NOTIFY_* operator config, docs and
changelog on docs-site."
git add .beans/
git commit -m "chore(beans): close calit-6rzr"
```

---

## Self-Review

Checked after writing, against `docs/superpowers/specs/2026-09-12-outbound-notifications-design.md`:

**Spec coverage.** §1 native spike → Task 1. §2 data model, encryption, routing rule → Tasks 2-3. §3 notification model, consent model, event set → Task 5 (model) + Task 6 (the eleven observers). §4 delivery path, error handling, failure visibility → Task 6. §5 configuration → Task 6 (properties + `NotifyConfig` + `ChannelPolicy`) and Task 9 (`.env.example`, docs). §6 owner UI → Tasks 7-8. §7 i18n → Task 5 (`msg`) and Tasks 7-8 (`adm`), de + he in the same change. §8 testing — delivery, failure, routing, owner scoping, allowlist, rendering, secrets — is covered by `ChannelDeliveryTest`, `ChannelRouterTest`, `ChannelOverrideTest`, `ChannelPolicyTest`, `ChannelMessageRendererTest`, `NotificationChannelTest` and `ChannelSettingsPageTest`; the locale-parity half is already enforced by the existing `MultiHostMessageParityTest`, so no new parity test is added. §9 accepted limitations are enforced (empty-custom rejected in Task 8) or documented (Task 9). §10 documentation obligations → Task 9.

**Two things the spec asks for that this plan does differently**, both recorded in *Spec Deviations* above with reasons: the masked-URL round-trip uses `redact` + equality instead of `parse`/`recompose`, and the private-target check is scoped to host-bearing channels so a bot token never reaches a DNS resolver.

**Type consistency.** `BookingSnapshot`/`HostDelivery`/`BookingSnapshotLoader` (Task 4) are the names Tasks 5 and 6 import. `ChannelPolicy.Check.ok()` is the method Tasks 6 and 7 call. `ChannelRow` is produced in Task 7 and consumed in Task 8. `NotificationChannelMeetingType.replaceLinks(meetingTypeId, ownChannelIds, keepChannelIds)` is declared in Task 2 and called with that argument order in Tasks 3 (test), 8 (both handlers). `HostNotification.Host.of(OwnerSettings)` is declared in Task 5 and called in Task 6.

**Sequencing.** Task 1 gates everything. Task 4 must land before Tasks 5-6 (they consume `BookingSnapshot`). Task 7 must land before Task 8 (`ChannelRow`, `ChannelAdmin`). Task 9 last.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-12-outbound-notification-channels.md`. Two execution options:

1. **Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.
