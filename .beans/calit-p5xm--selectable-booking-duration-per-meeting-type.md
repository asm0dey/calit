---
# calit-p5xm
title: Selectable booking duration per meeting type
status: todo
type: feature
priority: normal
created_at: 2026-08-15T22:57:11Z
updated_at: 2026-08-15T22:57:11Z
---

Upstream: https://github.com/asm0dey/calit/issues/119 (reporter h200101)

Meeting type currently has one fixed duration -> owners create near-duplicate types that differ only in length. Let owner define a set of allowed durations; booker picks one on the public page.

## Requirements (from issue)

- Owner configures allowed durations for a meeting type. Reporter asked for either min/max range or explicit list; explicit list preferred (avoids arbitrary lengths).
- Public booking page: booker selects duration, slots recomputed for that duration.
- Must work without JS (progressive enhancement): duration as a plain form control that re-submits / re-renders slot grid.

## Open questions (owner comment on issue, asm0dey)

- Buffers are per-duration in practice: 10 min before/after a 30-min meeting vs 45 min for a 120-min one. Does buffer become a per-duration setting, or a formula, or stay flat?
- Multi-host meetings: how does duration selection interact with host availability intersection?

Both unresolved upstream -> design work needed before implementation.

## Touch points

- `domain/MeetingType` (+ new Flyway `V26__*.sql`; never edit applied migrations)
- `availability/SlotService` — slot computation is duration-parameterised
- `web/PublicResource` + booking templates — duration picker, no-JS path
- `booking/BookingService` — persist chosen duration, validate against allowed set (do not trust form value)
- `email/EmailService` + `IcsBuilder` — event length follows chosen duration
- `google/` calendar sync — event end time
- i18n: new strings need `de` + `he` in `messages/*.properties`
- docs-site branch: usage docs for the new setting

## Todo

- [ ] Resolve buffer semantics per duration (ask reporter / decide)
- [ ] Resolve multi-host interaction
- [ ] Data model + migration
- [ ] SlotService duration parameterisation
- [ ] Public page duration picker (no-JS)
- [ ] Server-side validation of submitted duration
- [ ] Email / ICS / Google sync use chosen duration
- [ ] i18n de + he
- [ ] Tests
- [ ] docs-site update
