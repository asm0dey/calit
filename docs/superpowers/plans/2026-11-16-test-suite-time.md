# Test-Suite Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut `./mvnw clean test` wall time (baseline 258–272 s) by dropping two `@TestProfile`s and one Radicale-only app, and by fixing the test-side causes of the slowest classes.

**Architecture:** Every Quarkus test app costs one up-front build (~3.5 s) plus one in-JVM restart. The last run's surefire XML shows the restart is ~8–10 s, not 1.3–3.1 s: class time minus summed test time is 9.5 s for `InviteeErasureDisabledTest`, 10.2 s for `GoogleLoginResourceTest`, 8.0 s for `HealthResourceTest`, and 26.8 s for `OidcBridgeFlowTest`. So the slow classes are mostly the first class of each app paying its boot. Settings move out of `CommonFeaturesProfile` and `IsolatedSettingsProfile` into `@ApplicationScoped` beans that one test swaps (`@InjectMock` or `QuarkusMock.installMockForType`), so those tests run in the default app. Both CalDAV classes share one app through `@WithTestResource`.

**Tech Stack:** Quarkus 3.40.1.2 `@QuarkusTest`, `io.quarkus.test.InjectMock`, `io.quarkus.test.junit.QuarkusMock`, `io.quarkus.test.common.WithTestResource`, Mockito, AssertJ, RestAssured.

**Spec:** GitHub issue asm0dey/calit#345 (`gh issue view 345 --repo asm0dey/calit`).

## Global Constraints

- Branch `test/suite-time-345` from `origin/main`, not from `fix/high-severity-bugs`. Commit this plan on it.
- Keep `OidcCsrfProfile`. CSRF and the OIDC tenant are extension wiring fixed at startup.
- Production behaviour does not change. Every setting is still read once, at bean construction. `/signup` stays "restart to toggle".
- A bean whose methods are `@Transactional` (`GoogleSignInService`, `OidcSignInService`) must never be replaced by a hand-built `new` instance: `installMockForType` bypasses interceptors. Mock the config bean it reads instead.
- Assertions are AssertJ. Hamcrest only inside RestAssured `.body(...)`.
- JDK imports are `import module java.base;`.
- Per-task checks: `./mvnw -Paffected test-order:affected test`. The full `./mvnw clean test` must be green before the PR (CLAUDE.md).
- No user-facing change, so no `docs-site` or changelog entry.
- `$SCRATCH` in commands is one scratch directory outside the repo (e.g. the session scratchpad). Set it in each shell: `SCRATCH=<dir>`.

## Review Focus

1. **A swapped bean leaking into the next test.** `@InjectMock` and `installMockForType` last one test. `SignupResourceTest` (default 404) must still pass in the same fork after `SignupEnabledTest`. The full-suite run in Task 8 is the check, and `SignupResourceTest` must be in it.
2. **An operator editing the legal fragment file.** `LegalFragments` must read the file on every call, as `LegalResource.fragment` does today. Pinned by `LegalFragmentsTest#rereadsTheFileOnEveryCall` (Task 3).
3. **`calit.caldav.allow-private-hosts=true` reaching the default app.** `RadicaleResource` sets it. It must stay scoped to the CalDAV classes (`MATCHING_RESOURCES`), never global, or the SSRF guard tests stop meaning anything. Pinned in Task 5, step 3.
4. **Lost transactions in sign-in tests.** Swapping a `@Transactional` service for `new` fails with `TransactionRequiredException` or passes on rows that never commit. Task 2 mocks `SignupConfig` and keeps the real services.
5. **A mock that answers `null`.** An `@InjectMock` of a bean with several getters (`SiteInfo`, `PrivacyFacts`) returns `null` or `false` for every getter not stubbed, which can hide a broken page. Swap those beans for a real instance built with the test value (`installMockForType(new SiteInfo(...))`). Use `@InjectMock` only for `SignupConfig` and `CaptchaProviderConfig`.

---

### Task 1: Branch and baseline

**Files:** none (the numbers go into the PR body).

- [ ] **Step 1: Branch**

```bash
git fetch origin && git switch -c test/suite-time-345 origin/main
git add docs/superpowers/plans/2026-11-16-test-suite-time.md
git commit -m "docs(plan): test-suite time implementation plan (#345)"
```

- [ ] **Step 2: Baseline run**

Run: `start=$(date +%s); ./mvnw clean test 2>&1 | tee "$SCRATCH/baseline.log"; echo "wall=$(( $(date +%s)-start ))s"`
Expected: `BUILD SUCCESS`, wall time about 258–272 s. Record it.

- [ ] **Step 3: Per-class boot overhead**

Run this. It prints class time minus summed test time, which is the boot cost a class paid:

```bash
for f in target/surefire-reports/TEST-*.xml; do
  python3 - "$f" <<'EOF'
import sys, xml.etree.ElementTree as E
s = E.parse(sys.argv[1]).getroot()
over = float(s.get("time")) - sum(float(t.get("time")) for t in s.iter("testcase"))
print(f"{over:6.2f} {s.get('name')}")
EOF
done | sort -rn | head -12 | tee "$SCRATCH/baseline-overhead.txt"
grep -c "Creating container\|Reusing" "$SCRATCH/baseline.log"; grep -n "Creating container\|Reusing container" "$SCRATCH/baseline.log"
```

Expected: about six rows of 5–27 s (one per app), the rest under 1 s. Keep both files for Task 8.

---

### Task 2: Signup, OIDC admin group and scheduler grace without `CommonFeaturesProfile`

**Files:**
- Create: `src/main/java/site/asm0dey/calit/user/SignupConfig.java`
- Modify: `src/main/java/site/asm0dey/calit/web/SignupResource.java:29-56`, `src/main/java/site/asm0dey/calit/google/GoogleSignInService.java:29-58`, `src/main/java/site/asm0dey/calit/oidc/OidcSignInService.java:29-78`
- Modify (drop `@TestProfile(CommonFeaturesProfile.class)`): `web/SignupEnabledTest`, `google/GoogleSignInFlowTest`, `google/GoogleSignInProvisionTest`, `google/GoogleLoginResourceTest`, `oidc/OidcSignInServiceTest`, `scheduler/PendingExpiryGraceWindowTest`, `scheduler/ReminderGraceWindowTest` (all under `src/test/java/site/asm0dey/calit/`)
- Modify: any plain-JUnit test that calls `new GoogleSignInService(boolean)` / `new OidcSignInService(boolean, Optional)` / `new SignupResource(...)` (find with `grep -rn "new GoogleSignInService\|new OidcSignInService\|new SignupResource" src/test`)

**Interfaces:**
- Produces: `@ApplicationScoped public class SignupConfig { @Inject public SignupConfig(@ConfigProperty(name = "calit.signup.enabled", defaultValue = "false") boolean enabled); public boolean enabled(); }`
- Produces: `SignupResource`, `GoogleSignInService` and `OidcSignInService` take `SignupConfig` in place of their `boolean signupEnabled` constructor parameter. `OidcSignInService` keeps `Optional<String> adminGroup` as its second parameter.

- [ ] **Step 1: Switch the seven test classes to the default app**

In each class: delete `@TestProfile(CommonFeaturesProfile.class)` and its import. In the four signup-dependent HTTP/service classes (`SignupEnabledTest`, `GoogleSignInFlowTest`, `GoogleSignInProvisionTest`, `GoogleLoginResourceTest`) add:

```java
@InjectMock
SignupConfig signup;

@BeforeEach
void signupOn() {
    when(signup.enabled()).thenReturn(true);
}
```

`OidcSignInServiceTest` calls the service directly, never over HTTP, and needs an admin group as well. So it builds its own instance instead of injecting one: `service = new OidcSignInService(signupOn, Optional.of("calit-admins"))`, where `signupOn` is a plain `Mockito.mock(SignupConfig.class)` stubbed to `true`. A `new` instance has no `@Transactional` interceptor, so wrap each `service.resolveOrProvision(...)` call in `QuarkusTransaction.requiringNew().call(() -> ...)`. This is the one place the Global Constraints' "no `new` for `@Transactional` beans" rule bends: the instance is never installed into CDI, and the test supplies the transaction itself.

The two grace tests: delete the profile. In `@BeforeEach`, build the scheduler the test exercises with the grace value the profile gave (`120`), from injected collaborators. Both schedulers use programmatic `QuarkusTransaction`, so a `new` instance is safe:

```java
@Inject EntityManager em;
@Inject EmailService emailService;
@Inject NotificationDispatcher channels;
// PendingExpiryGraceWindowTest
scheduler = new PendingExpiryScheduler(em, emailService, channels, 24, 120);
// ReminderGraceWindowTest
scheduler = new ReminderScheduler(em, emailService, channels, 1440, 120);
```

Replace the `@Inject PendingExpiryScheduler` / `@Inject ReminderScheduler` field with that local instance. Keep every assertion.

- [ ] **Step 2: Run them to see the signup classes fail**

Run: `./mvnw test -Dtest='SignupEnabledTest,GoogleSignIn*Test,GoogleLoginResourceTest,OidcSignInServiceTest,*GraceWindowTest'`
Expected: compile failure, `cannot find symbol: class SignupConfig`.

- [ ] **Step 3: Implement `SignupConfig` and thread it through the three consumers**

Each consumer replaces `if (!signupEnabled)` with `if (!signup.enabled())`. Javadoc on `SignupConfig`: one line saying it exists so a test can switch signup on without a profile restart, and that the value is still read once at startup. Fix the plain-JUnit constructor callers found by the grep: they pass `new SignupConfig(true|false)`.

- [ ] **Step 4: Run them, plus the default-off signup test**

Run: `./mvnw test -Dtest='SignupEnabledTest,SignupResourceTest,GoogleSignIn*Test,GoogleLoginResourceTest,OidcSignInServiceTest,*GraceWindowTest'`
Expected: `BUILD SUCCESS`. `SignupResourceTest` still sees 404 on `/signup`. The log shows no `CommonFeaturesProfile` restart for these classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/user/SignupConfig.java src/main/java/site/asm0dey/calit/web/SignupResource.java \
  src/main/java/site/asm0dey/calit/google/GoogleSignInService.java src/main/java/site/asm0dey/calit/oidc/OidcSignInService.java \
  src/test/java   # review `git status` first: only the files this task names
git commit -m "test: signup, OIDC admin group and grace tests run in the default app (#345)"
```

---

### Task 3: Legal fragments and site verification without `CommonFeaturesProfile`; delete it

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/LegalFragments.java`
- Modify: `src/main/java/site/asm0dey/calit/web/LegalResource.java:32-90` (move the paths and `fragment(Optional)` out)
- Create: `src/test/java/site/asm0dey/calit/web/LegalFragmentsTest.java` (plain JUnit)
- Modify: `privacy/PrivacyPolicyOverrideTest`, `web/GoogleVerificationMetaTest`
- Delete: `privacy/PrivacyPolicyUnreadableFragmentTest`, `privacy/PrivacyPolicyMalformedFragmentPathTest` (their cases move into `LegalFragmentsTest`), `web/CommonFeaturesProfile.java`

**Interfaces:**
- Produces: `@ApplicationScoped public class LegalFragments { @Inject public LegalFragments(@ConfigProperty(name = "app.privacy-policy-path") Optional<String> privacyPolicyPath, @ConfigProperty(name = "app.terms-path") Optional<String> termsPath); public RawString privacy(); public RawString terms(); }`. Each returns `null` when unset or unreadable. The body and log line are `LegalResource.fragment` moved as is, javadoc included.
- Produces: `LegalResource(OgCards ogCards, LegalFragments fragments)`.

- [ ] **Step 1: Write `LegalFragmentsTest`**

```java
@Test void unsetPathServesTheShippedCopy()            // new LegalFragments(Optional.empty(), Optional.of(" ")) → privacy() and terms() are null
@Test void missingFileServesTheShippedCopy()          // Optional.of("/nonexistent/path/does-not-exist.html") → privacy() isNull()
@Test void malformedPathServesTheShippedCopy()        // terms path "bad\0path.html" → terms() isNull(), no exception
@Test void readableFileReplacesTheBody(@TempDir Path d) // writes "<h1>OPERATOR SUPPLIED POLICY</h1>" → privacy().getValue() isEqualTo that
@Test void rereadsTheFileOnEveryCall(@TempDir Path d)   // write "v1", call, write "v2", call → second getValue() isEqualTo "v2"
```

- [ ] **Step 2: Change the two HTTP tests**

Drop the profile from both. `PrivacyPolicyOverrideTest` in `@BeforeEach`: write the fragment to a `@TempDir` file, then `QuarkusMock.installMockForType(new LegalFragments(Optional.of(file.toString()), Optional.empty()), LegalFragments.class)`. Keep its assertion.

`GoogleVerificationMetaTest`: inject `@ConfigProperty(name = "app.base-url") String baseUrl`, then `QuarkusMock.installMockForType(new SiteInfo(Optional.of("tok_calit_test_123"), Optional.empty(), Optional.empty(), baseUrl), SiteInfo.class)`.

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='LegalFragmentsTest,PrivacyPolicyOverrideTest,GoogleVerificationMetaTest'`
Expected: compile failure on `LegalFragments`.

- [ ] **Step 4: Implement `LegalFragments`, rewire `LegalResource`, delete the two moved tests and `CommonFeaturesProfile`**

Run first: `grep -rn CommonFeaturesProfile src/test`. Expected: only the profile file itself. Then delete it.

- [ ] **Step 5: Run them plus every legal-page test**

Run: `./mvnw test -Dtest='LegalFragmentsTest,PrivacyPolicy*Test,GoogleVerificationMetaTest,LegalResource*Test'`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git commit -m "test: legal fragments and verification meta run in the default app; drop CommonFeaturesProfile (#345)"
```

---

### Task 4: Move `IsolatedSettingsProfile` tests to swapped beans; delete it

**Files:**
- Modify (drop the profile): `privacy/PrivacyPolicyTurnstileConfiguredTest`, `privacy/PrivacyPolicySmtpConfiguredTest`, `privacy/PrivacyPolicyGoogleUnconfiguredTest`, `privacy/InviteeErasureDisabledTest`, `scheduler/RetentionSchedulerInstanceDefaultTest`
- Delete: `src/test/java/site/asm0dey/calit/web/IsolatedSettingsProfile.java`
- Modify: `web/OidcCsrfProfile.java` javadoc (drop any mention of the deleted profiles)

**Interfaces:**
- Consumes: existing constructors, unchanged: `PrivacyFacts(Optional<String> googleClientId, boolean oidcEnabled, Optional<String> smtpHost, boolean mailerMocked, CaptchaProviderConfig, PrivacyConfig)`, `PrivacyConfig(boolean inviteeErasure, Optional<Integer> retentionDays)`, `SiteInfo(Optional<String>, Optional<String>, Optional<String>, String)`.

The setting each class needs, and how to swap it in `@BeforeEach` (inject the real `CaptchaProviderConfig`/`PrivacyConfig` and `app.base-url` where a constructor needs them):

| Class | Swap |
|---|---|
| `PrivacyPolicyTurnstileConfiguredTest` | `@InjectMock CaptchaProviderConfig`, `when(provider()).thenReturn("turnstile")` (the `AltchaChallengeTest` pattern) |
| `PrivacyPolicySmtpConfiguredTest` | `installMockForType(new PrivacyFacts(Optional.of("test-client-id"), false, Optional.of("mail.example.org"), false, captcha, privacyConfig), PrivacyFacts.class)` |
| `PrivacyPolicyGoogleUnconfiguredTest` | same, with `Optional.of(" ")` client id, `Optional.empty()` host, `true` mocked. Cut its javadoc paragraph on the `@ConfigMapping` single-space workaround: the mapping is no longer involved |
| `InviteeErasureDisabledTest` | `installMockForType(new PrivacyConfig(false, Optional.empty()), PrivacyConfig.class)` + `installMockForType(new SiteInfo(Optional.empty(), Optional.empty(), Optional.of("privacy@example.com"), baseUrl), SiteInfo.class)` |
| `RetentionSchedulerInstanceDefaultTest` | `installMockForType(new PrivacyConfig(true, Optional.of(14)), PrivacyConfig.class)` |

The profile's `app.base-url` override is asserted by no test (`grep -rn cal.example.test src/test` finds only `GoogleRedirectUriCustomBaseTest`, which sets its own value), so it goes with the profile. Real SMTP (`quarkus.mailer.mock=false`) goes too. That also stops `ErasureFixtures.seedPastBooking` from queueing mail to `mail.example.org` under this profile, which may be part of `InviteeErasureDisabledTest`'s 9.5 s overhead.

- [ ] **Step 1: Make the five edits above, then delete the profile**

Run first: `grep -rn IsolatedSettingsProfile src/test`. Expected: only the profile file and `OidcCsrfProfile`'s javadoc.

- [ ] **Step 2: Run them**

Run: `./mvnw test -Dtest='PrivacyPolicy*Test,InviteeErasure*Test,RetentionScheduler*Test,PrivacyFacts*Test'`
Expected: `BUILD SUCCESS`, the same test count as before for these classes.

- [ ] **Step 3: Prove each swap took effect**

Temporarily change one stubbed value per class (e.g. `"turnstile"` → `"none"`, `14` → `null`) and run that class. Expected: it fails on the assertion that depends on the value. Revert. This shows no test passes on the default value by accident.

- [ ] **Step 4: Commit**

```bash
git commit -m "test: isolated-setting tests swap their config bean; drop IsolatedSettingsProfile (#345)"
```

---

### Task 5: One app and one Radicale container for both CalDAV classes

**Files:**
- Modify: `src/test/java/site/asm0dey/calit/caldav/CaldavClientTest.java:16`, `src/test/java/site/asm0dey/calit/caldav/CaldavCalendarPortTest.java:32`

- [ ] **Step 1: Replace the annotation**

On both classes, replace `@QuarkusTestResource(value = RadicaleResource.class, restrictToAnnotatedClass = true)` with `@WithTestResource(RadicaleResource.class)` (`io.quarkus.test.common.WithTestResource`, default `scope = MATCHING_RESOURCES`). Classes with the same resource set share one app. Do not make it unrestricted: `RadicaleResource` sets `calit.caldav.allow-private-hosts=true`. Leave `CaldavFlowIT` alone; it runs under failsafe.

- [ ] **Step 2: Run both**

Run: `./mvnw test -Dtest='CaldavClientTest,CaldavCalendarPortTest' 2>&1 | tee "$SCRATCH/caldav.log"; grep -c "tomsquest/docker-radicale" "$SCRATCH/caldav.log"`
Expected: `BUILD SUCCESS`, and one Radicale container start in the log, not two.

- [ ] **Step 3: Check the private-host flag did not reach the default app**

Run: `./mvnw test -Dtest='CaldavClientTest,CaldavHttpTest,CaldavCalendarPortTest'`
Expected: `BUILD SUCCESS`. Also look for a test that asserts a private host is refused through the real config (`grep -rln "private" src/test/java/site/asm0dey/calit/caldav`). If none exists in the default app, add `CaldavPrivateHostGuardTest#defaultAppRefusesPrivateHosts`: `@QuarkusTest`, inject the bean that reads `calit.caldav.allow-private-hosts`, and assert its value `isFalse()`.

- [ ] **Step 4: Commit**

```bash
git commit -m "test: CalDAV classes share one app and one Radicale container (#345)"
```

---

### Task 6: `/q/health/ready` test stops dialling Google

The issue blames `MailHealth`/SMTP. In `%test` that is wrong: `SmtpHealthCheck.call()` returns early when `quarkus.mailer.mock=true`. The network call is `GoogleHealthCheck`. `%test.google.oauth.client-id=test-client-id` is non-blank, so it opens a real TCP socket to `oauth2.googleapis.com:443` with a 2 s timeout. That took 1.33 s on the baseline run, needs internet access, and waits out the full 2 s offline.

**Files:**
- Modify: `src/test/java/site/asm0dey/calit/HealthResourceTest.java`

- [ ] **Step 1: Swap the Google check for the readiness test**

In `@BeforeEach`: `QuarkusMock.installMockForType(stub, GoogleHealthCheck.class, Readiness.Literal.INSTANCE)`. `stub` is a Mockito mock whose `call()` returns `HealthCheckResponse.named("Google").up().withData("state", "not-configured").build()`. If the qualifier overload throws because `@Readiness` is not a CDI qualifier on this bean, use `@InjectMock GoogleHealthCheck` instead and stub the same `call()`.

- [ ] **Step 2: Run it**

Run: `./mvnw test -Dtest=HealthResourceTest`
Expected: `BUILD SUCCESS`. `readinessProbeIsUp` is under 0.3 s in `target/surefire-reports/TEST-site.asm0dey.calit.HealthResourceTest.xml`.

- [ ] **Step 3: Commit**

```bash
git commit -m "test: readiness test no longer opens a socket to Google (#345)"
```

---

### Task 7: Find out why `OidcBridgeFlowTest` and `BookingInviteRoutesTest` are slow; fix what is test-side

Baseline numbers: `OidcBridgeFlowTest` spends 26.8 s outside its one 0.8 s test. That is about 17 s more than any other app's boot. `BookingInviteRoutesTest#invitePickerHidesSlug` takes 5.6 s for four `GET /admin?invite=…` renders (~1.4 s each), and `durationSwitchKeepsInvite` takes 2.9 s.

**Files:** decided by the findings. Expected to be `src/test/java/site/asm0dey/calit/oidc/OidcBridgeFlowTest.java`, `web/OidcCsrfProfile.java` and `web/BookingInviteRoutesTest.java`.

- [ ] **Step 1: Time the OIDC boot**

Run: `./mvnw test -Dtest=OidcBridgeFlowTest -Dquarkus.log.category.\"io.quarkus.oidc\".level=DEBUG 2>&1 | tee "$SCRATCH/oidc.log"`
Read the timestamps between the restart start, the WireMock start, the OIDC discovery request and `started in`. Name the step that eats the ~17 s. Candidates to confirm or rule out: OIDC Dev Services (Keycloak) starting a container, discovery retries against WireMock before it listens (`quarkus.oidc.connection-delay`), and the WireMock JVM start.

- [ ] **Step 2: Fix it if test-side**

Examples: an override in `OidcCsrfProfile` (e.g. `quarkus.keycloak.devservices.enabled=false`, or a connection-retry setting), or a resource order change. Rerun step 1. Expected: overhead under 12 s. If the cause is in an extension and cannot be fixed test-side, write the finding as a comment on #345 instead of changing code.

- [ ] **Step 3: Time the invite-picker renders**

Run: `./mvnw test -Dtest='BookingInviteRoutesTest#invitePickerHidesSlug'` twice: once as is, once with the loop cut to its first query. If one render alone costs ~1.4 s, the cost is slot computation (`SlotService` over the type's horizon with the seeded durations), not warm-up. Then find the seed's horizon (`seed(SLUG)` in the same class) and make it the shortest one that still contains `SLOT_09`/`SLOT_10`. If only the first render is slow, it is first-request warm-up; say so on #345 and change nothing.

- [ ] **Step 4: Run the class**

Run: `./mvnw test -Dtest='BookingInviteRoutesTest,OidcBridgeFlowTest'`
Expected: `BUILD SUCCESS` with every assertion unchanged.

- [ ] **Step 5: Commit (only if code changed)**

```bash
git commit -m "test: <the cause found> (#345)"
```

---

### Task 8: Measure, container reuse, document

**Files:**
- Modify: `CLAUDE.md` (Tests section)

- [ ] **Step 1: Full run, same commands as Task 1**

Run Task 1 steps 2–3 again, writing `after.log` / `after-overhead.txt`.
Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Overhead rows drop from about six to about three: default app, CalDAV app, `OidcCsrfProfile`. Wall time drops by roughly 30–40 s (three fewer apps × ~3.5 s build + ~9 s restart).

- [ ] **Step 2: Container reuse**

`~/.testcontainers.properties` on this machine already has `testcontainers.reuse.enable=true`, so the issue's guess is ruled out here. Grep `after.log` for `Creating container` / `Reusing container` for the Postgres image. If it still says "Creating" on a second consecutive run, find out why the reuse hash changes (run twice and compare the container labels with `docker inspect`). Fix it, or report it on #345. Document in CLAUDE.md only if a cause is found.

- [ ] **Step 3: CLAUDE.md**

Add one bullet to the Tests section: a test that needs a non-default setting swaps the `@ApplicationScoped` bean that reads it (`@InjectMock`, or `QuarkusMock.installMockForType(new X(...))` for beans with several getters) instead of adding a `@TestProfile`, because each profile costs a build plus a ~9 s restart. `@TestProfile` is only for extension wiring fixed at startup (`OidcCsrfProfile`). Then record it: `precedent check --topic test-config-switching --chose swap-config-bean`, then `precedent record` with `--rejected "@TestProfile per setting"` and the rationale (see the precedent-cli-via-uv memory for how to invoke it).

- [ ] **Step 4: Commit and open the PR**

```bash
git add CLAUDE.md
git commit -m "docs(claude): swap config beans instead of adding test profiles (#345)"
```

PR body: before/after wall time and overhead tables, what Task 7 found, `Closes #345`, a show-me diagram (see memory), AI disclosure.
