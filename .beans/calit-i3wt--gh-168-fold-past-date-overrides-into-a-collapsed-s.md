---
# calit-i3wt
title: 'GH #168: fold past date overrides into a collapsed section'
status: in-progress
type: feature
priority: normal
created_at: 2026-09-05T13:16:10Z
updated_at: 2026-09-05T13:31:18Z
---

The /me/date-overrides page lists every override the owner has ever created, oldest mixed with newest, so a host with many of them cannot find the upcoming ones. Show upcoming (date >= today in the owner's timezone) first, and move past ones into a native <details> collapse labelled with their count. Nothing is deleted.

Plan: docs/superpowers/plans/2026-09-05-past-date-overrides.md

- [x] Add adm_dateOverrides_past_summary with de + he translations
- [x] Split overrides into upcoming/past in AdminResource
- [x] Extract the override card into a Qute partial and render the past collapse
- [ ] Tests for the split (placement, not substring)
- [ ] Browser + no-JS check
- [ ] docs-site: changelog Unreleased bullet + availability.md paragraph
