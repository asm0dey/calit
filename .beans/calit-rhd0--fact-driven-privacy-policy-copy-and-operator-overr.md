---
# calit-rhd0
title: Fact-driven privacy policy copy and operator overrides
status: completed
type: task
priority: normal
created_at: 2026-09-16T18:24:38Z
updated_at: 2026-09-16T18:33:45Z
parent: calit-l3fk
---

Task 11: PrivacyFacts, LegalResource fragment overrides, conditional policy copy, sensitive-field warning

- [x] Write PrivacyPolicyRenderTest (default %test profile — Google IS configured there, per R5)
- [x] Write PrivacyFacts (`@Named("privacy")`)
- [x] Rewrite privacy.html: conditional Google/sharing/retention sections, fixed deletion claim, deleted_username hash disclosure, invitee-erasure scope, retention facts, ponytail comment update
- [x] Rewrite terms.html: override wrapper, "Your account" deletion section (CALIT_LEGAL_TERMS marker already present)
- [x] LegalResource: operator fragment override (RawString, read-per-request, unreadable-path fallback)
- [x] application.properties / .env.example: app.privacy-policy-path / app.terms-path
- [x] AdminMessages.adm_fields_sensitive_warning + German translation + bookingFields.html warning box
- [x] MultiHostMessageParityTest: adm_fields_sensitive_warning added to HE_DEFERRED_ADMIN_KEYS (R10)
- [x] R3: top-level test classes instead of nested statics (PrivacyPolicyGoogleUnconfiguredTest, PrivacyPolicyOverrideTest, PrivacyPolicyUnreadableFragmentTest)
- [x] R5: default-profile test asserts Google sections PRESENT; separate top-level profile (client-id=" ") asserts ABSENT
- [x] Full suite green: 1229 tests, 0 failures, 0 errors

## Summary of Changes

- Created `PrivacyFacts` (`@Named("privacy")`, `@ApplicationScoped`) exposing what THIS deployment
  actually does to Qute: `isGoogleConfigured()` (same non-blank-client-id signal as
  `LoginResource`), `isOidcConfigured()`, `getSmtpHost()` (Optional `quarkus.mailer.host`, unset
  outside `%prod`), `isSignupOpen()`, `isInviteeErasureEnabled()`, `getRetentionDays()`,
  `isAnyChannelConfigured()` (live `NotificationChannel.count()`).
- Rewrote `privacy.html`: Google/"Limited Use" sections and the Google sharing/retention bullets are
  now conditional on `inject:privacy.googleConfigured`; the "Data sharing" list is built from live
  facts (Google, SMTP host, any notification channel); the false "deleting a user account removes
  that user's scheduling data" claim is now backed by `/me/settings/delete` and lists exactly what's
  removed; added the deleted-username one-way-hash disclosure (V35/Task 7); the invitee-erasure
  bullet now states bookings aren't linked by email so erasure reaches only the one booking; the
  retention bullet renders the instance default in days when set (capped at 36,500) with a
  `CALIT_RETENTION_FOREVER` marker comment when unset; added the parked-mail/token retention line
  (Task 10). Ponytail comment rewritten to say this page — not the docs-site markdown — is now
  authoritative for a running deployment.
- Rewrote `terms.html`: added an operator-override wrapper (same mechanism as privacy.html) and an
  honest "Your account" deletion section. `CALIT_LEGAL_TERMS` marker was already present.
- `LegalResource`: both templates take a `RawString override` parameter; `fragment(Optional<String>)`
  reads `app.privacy-policy-path` / `app.terms-path` fresh on every request, returns null (renders the
  shipped copy) on a blank path or unreadable file, logging a warning in the latter case so `/privacy`
  never goes down (the Google consent screen links it).
- `application.properties` / `.env.example`: added `app.privacy-policy-path` / `app.terms-path` (env
  `PRIVACY_POLICY_PATH` / `TERMS_PATH`), beside the existing operator-name/privacy-contact block.
- `AdminMessages.adm_fields_sensitive_warning` (+ German translation) and a warning alert above the
  add-booking-field form in `bookingFields.html`, since owners can ask Art. 9 special-category
  questions in a free-text field and calit stores the answer as plain text.
- `MultiHostMessageParityTest`: added `adm_fields_sensitive_warning` to `HE_DEFERRED_ADMIN_KEYS`
  (German ships now, Hebrew deferred per the epic's existing pattern — R10).

### Controller-ruling deviations from the brief (documented, not silent)

- **R3** — the brief's nested `static class OverrideFile` test would never run under surefire
  (skips `$`-named classes) and Quarkus restarts per `@TestProfile` anyway. Split into three
  top-level classes: `PrivacyPolicyGoogleUnconfiguredTest`, `PrivacyPolicyOverrideTest`,
  `PrivacyPolicyUnreadableFragmentTest` (added — brief asked to "test the unreadable-path fallback
  too").
- **R5** — `%test.google.oauth.client-id=test-client-id` means Google IS configured under the
  default `%test` profile (same signal `LoginResource` uses). `PrivacyPolicyRenderTest`'s Google
  test now asserts PRESENCE in the default profile;
  `PrivacyPolicyGoogleUnconfiguredTest` asserts ABSENCE under a separate `@TestProfile` that
  overrides `google.oauth.client-id` to a single space `" "` — non-blank so
  `GoogleOAuthConfig`'s `@ConfigMapping` (which rejects a literal empty string) still boots, but
  blank under `PrivacyFacts`'s `String::isBlank` check.
- **R10** — `adm_fields_sensitive_warning` added to `HE_DEFERRED_ADMIN_KEYS`.

### Verified before writing

- `google.oauth.client-id` (application.properties:20, `%test` override :24), `calit.oidc.enabled`
  (:143, `%test`:162 = false), `calit.signup.enabled` (:61), `quarkus.mailer.host` (only set
  `%prod`, confirmed via `Optional`), `app.privacy-contact` / `app.operator-name` (:79-81).
  `@Named("privacy")` has no collision (existing: `mailHealth`, `build`, `owner`, `nav`, `site`).
  `{inject:site.*}` pattern (`SiteInfo`) matched for the new bean.
- `GooglePageResource.disconnect` confirmed to delete tokens only, no revoke call (matches global
  constraints deviation #2 — copy says "does not withdraw the grant at Google").
- No other caller of `LegalResource.Templates.privacy/terms` besides `LegalResource` itself.
- `terms.html` already had `<!-- CALIT_LEGAL_TERMS -->` — no addition needed.

## Test results

- Focused: `PrivacyPolicyRenderTest` (6), `PrivacyPolicyGoogleUnconfiguredTest` (1),
  `PrivacyPolicyOverrideTest` (1), `PrivacyPolicyUnreadableFragmentTest` (1),
  `MultiHostMessageParityTest` (4) — 13/13 passing, output pristine (the unreadable-fragment test's
  expected WARN log is the assertion, not noise).
- Full suite: `mvn test` — 1229 tests, 0 failures, 0 errors, BUILD SUCCESS, 3:36 min.
