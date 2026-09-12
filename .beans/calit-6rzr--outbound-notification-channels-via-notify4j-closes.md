---
# calit-6rzr
title: 'Outbound notification channels via notify4j (closes #194)'
status: completed
type: feature
priority: normal
created_at: 2026-09-12T11:27:07Z
updated_at: 2026-09-12T15:18:20Z
---

Per-owner notification channel URLs delivered through notify4j-core, routed per meeting type. Design: docs/superpowers/specs/2026-09-12-outbound-notifications-design.md. Precedent: #notify4j-core-for-multi-channel-booking-notifica-1789206712

## Implementation plan

docs/superpowers/plans/2026-09-12-outbound-notification-channels.md

- [x] 1. Native-image spike (gate) — notify4j-core 1.1.1 builds and delivers in the native image
- [x] 2. Migration V32 + NotificationChannel / NotificationChannelMeetingType entities
- [x] 3. ChannelRouter — per-host, per-meeting-type routing rule
- [x] 4. Promote BookingSnapshot + loader out of EmailService (IDE refactoring via steroid)
- [x] 5. HostNotification model, ChannelMessageRenderer, msg keys + de/he
- [x] 6. NotifyConfig, ChannelPolicy, NotificationDispatcher, ChannelSender
- [x] 7. /me/settings channel list (add, delete, send test)
- [x] 8. Per-meeting-type override (creator + co-host)
- [x] 9. .env.example, docs-site pages, changelog Unreleased bullet

## Native spike

notify4j-core 1.1.1 native build: PASS. POST delivered from the native binary; reflection/init log gate clean. Build arg needed: --initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig (the predicted failure did fire — GraalVM UnsupportedFeatureException: a live jdk.internal.net.http.HttpClientFacade reachable via HttpClientConfig.defaults() was embedded in the build-time image heap).

Verified API surface (org.alexmond:notify4j-core:1.1.1) matches the plan's assumed signatures — see docs/superpowers/sdd/2026-09-12-outbound-notification-channels/task-1-report.md for the full symbol table. One nuance for later tasks: HttpClientConfig.defaults()/HttpClientConfig.of(...) are static factory METHODS, not a public static final DEFAULT field as the plan speculated; the run-time-init fix targets the whole HttpClientConfig class regardless.

## Tasks 2 + 3

Both done, TDD per brief, full details/output in docs/superpowers/sdd/2026-09-12-outbound-notification-channels/task-2-report.md.

- Task 2 (e04897d): V32 migration + `NotificationChannel` / `NotificationChannelMeetingType` entities. `NotificationChannelTest`: 3/3 green.
- Task 3 (f59ce15): `ChannelRouter`. `ChannelRouterTest`: 5/5 green.
- Found and fixed a real bug in both briefs' verbatim test bodies: `notification_channel.owner_id` carries a hard FK to `app_user` (consistent with every other owner-scoped table since V8), but the given test code persisted rows for owner id 2 without that user existing yet — either missing the `MultiHostFixtures.enabledUser` seed entirely (Task 2's test) or calling it too late, after channels for HOST_B were already persisted (2 of Task 3's 5 tests). Fixed by seeding/reordering; no assertion was weakened.

## Task 4

`BookingSnapshot`/`HostDelivery`/`BookingSnapshotLoader` promoted out of `EmailService` (4d228d7b).

## Task 5

`HostNotification` sealed interface (11 event records + `Host`) and `ChannelMessageRenderer`, brief followed verbatim: full details in docs/superpowers/sdd/2026-09-12-outbound-notification-channels/task-5-report.md. `ChannelMessageRendererTest`: 4/4 green; `MultiHostMessageParityTest`: 4/4 green (nine new `channel_*` keys present in `AppMessages` + both `msg_de.properties`/`msg_he.properties`, no orphans). Full suite: 1103 tests, 0 failures. `AppMessages.java` edited through MCP Steroid per the IDE-editing rule.

## Follow-up requested during execution

- [x] /me/settings channel section links to a docs page listing the supported channel URL formats. The per-row
      `docsUrl()` link only shows for channels that already exist, so a first-time host sees an empty field with
      no guidance. Link target: https://asm0dey.github.io/calit/usage/notification-channels/ (the page Task 9
      creates). New key `adm_settings_channels_help` + de/he, rendered near the section heading so it is visible
      when the list is empty. Assigned to Task 9 so the page and the link land together.

## Task 8

Per-meeting-type routing override on both pages (`POST /me/meeting-types/{id}/notifications` for the creator,
`POST /me/shared/{typeId}/notifications` for a co-host), radio + checkboxes, no JS. Full details in
docs/superpowers/sdd/2026-09-12-outbound-notification-channels/task-8-report.md. `ChannelOverrideTest`: 4/4 green
(404 on all four before the handlers existed); `MultiHostMessageParityTest`: 4/4 green (five new
`adm_detail_notifications_*` keys with de/he, no orphans). Full suite: 1125 tests, 0 failures. `AdminResource`,
`SharedMeetingsResource` and `AdminMessages` edited through MCP Steroid per the IDE-editing rule.

## Task 8 fix round 1

Review came back approved (all seven security verdicts pass); two Minors addressed. Added
`ChannelOverrideTest.oneHostsSaveNeverDeletesAnotherHostsLinkRows` — the only test that can observe
`replaceLinks`' per-host DELETE scope, because it gives the non-acting host a REAL override before the acting
host saves. Proven to discriminate by mutation: unscoping the DELETE to `meetingTypeId = ?1` fails that test
alone (10 run, 1 failure) while every other override and router test still passes. Also `.distinct()` on `keep`
in both handlers, so a crafted duplicate `channelIds` cannot violate V32's `uq_ncmt` and 500 the save. Full
suite: 1126 tests, 0 failures.

## Task 9

.env.example: NOTIFY_ALLOWED_SCHEMES/NOTIFY_ALLOW_PRIVATE/NOTIFY_MAX_ATTEMPTS appended after the CAPTCHA
block (20edd41). Docs (docs-site branch, throwaway worktree, 746660f6): new
usage/notification-channels.md at the exact required path (per-provider URL instructions, /me/settings
flow, per-host per-meeting-type override, delete-to-disable, channels-bypass-email-flag, fail-visible-
not-why, mask round-trip + same-scheme-same-mask, generic webhook drops the title/reports ALERT,
NOTIFY_MAX_ATTEMPTS worker-pressure note); installation/configuration.md gained an 'Outbound notification
channels' section with the same three variables; releases/changelog.md gained an Unreleased section with
the brief's two bullets and upgrade note, PR links as `#NNN` placeholders pending the real PR number.
UI follow-up (6319de1): adm_settings_channels_help (+de/he) links the empty channel row to the new docs
page; adm_detail_notifications_save (+de/he) replaces the borrowed adm_settings_channels_save on both
notification-routing forms (meetingTypeDetail.html, sharedAvailability.html). Verification:
ChannelPolicyTest+ChannelDeliveryTest 10/10, MultiHostMessageParityTest+ChannelSettingsPageTest+
ChannelOverrideTest 17/17, full suite 1126/1126 (unchanged from baseline — no new tests, UI-string-only
change). spotless:apply: no changes needed.

## Summary of Changes

Per-owner notification channels delivered via notify4j-core: migration V32 (notification_channel +
notification_channel_meeting_type), encrypted URLs, per-host per-meeting-type routing, 11 booking
events, /me/settings management UI with an inline send-test, NOTIFY_* operator config, docs and
changelog on docs-site.
