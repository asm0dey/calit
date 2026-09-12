---
# calit-6rzr
title: 'Outbound notification channels via notify4j (closes #194)'
status: in-progress
type: feature
priority: normal
created_at: 2026-09-12T11:27:07Z
updated_at: 2026-09-12T12:33:48Z
---

Per-owner notification channel URLs delivered through notify4j-core, routed per meeting type. Design: docs/superpowers/specs/2026-09-12-outbound-notifications-design.md. Precedent: #notify4j-core-for-multi-channel-booking-notifica-1789206712

## Implementation plan

docs/superpowers/plans/2026-09-12-outbound-notification-channels.md

- [x] 1. Native-image spike (gate) — notify4j-core 1.1.1 builds and delivers in the native image
- [ ] 2. Migration V32 + NotificationChannel / NotificationChannelMeetingType entities
- [ ] 3. ChannelRouter — per-host, per-meeting-type routing rule
- [ ] 4. Promote BookingSnapshot + loader out of EmailService (IDE refactoring via steroid)
- [ ] 5. HostNotification model, ChannelMessageRenderer, msg keys + de/he
- [ ] 6. NotifyConfig, ChannelPolicy, NotificationDispatcher, ChannelSender
- [ ] 7. /me/settings channel list (add, delete, send test)
- [ ] 8. Per-meeting-type override (creator + co-host)
- [ ] 9. .env.example, docs-site pages, changelog Unreleased bullet

## Native spike

notify4j-core 1.1.1 native build: PASS. POST delivered from the native binary; reflection/init log gate clean. Build arg needed: --initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig (the predicted failure did fire — GraalVM UnsupportedFeatureException: a live jdk.internal.net.http.HttpClientFacade reachable via HttpClientConfig.defaults() was embedded in the build-time image heap).

Verified API surface (org.alexmond:notify4j-core:1.1.1) matches the plan's assumed signatures — see docs/superpowers/sdd/2026-09-12-outbound-notification-channels/task-1-report.md for the full symbol table. One nuance for later tasks: HttpClientConfig.defaults()/HttpClientConfig.of(...) are static factory METHODS, not a public static final DEFAULT field as the plan speculated; the run-time-init fix targets the whole HttpClientConfig class regardless.
