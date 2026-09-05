---
# calit-sijz
title: Fold past overrides on the meeting-type detail page too
status: todo
type: task
priority: low
created_at: 2026-09-05T13:59:02Z
updated_at: 2026-09-05T13:59:02Z
---

GH #168 split /me/date-overrides into upcoming and a collapsed "Past overrides (N)" section, but left the per-type override list in templates/AdminResource/meetingTypeDetail.html unsplit. It is already inside a collapsed accordion and scoped to one type, so it is a smaller problem — the final reviewer agreed it is not a merge blocker.

detailInstance() would split overridesForType(id) the same way dateOverridesInstance() splits the global list.

Reuse is NOT free, contrary to the first draft of this note. The detail-page card differs from _dateOverrideCard.html in four ways: it uses bg-base-200 not bg-base-100; it prints no global/per-type label; it posts to /me/meeting-types/{type.id}/date-overrides/{o.id}/delete; and it uses the adm_detail_* key family, not adm_dateOverrides_*. So this means either parameterising _dateOverrideCard.html (card class, delete action, key set) or writing a second partial — decide which before starting.
