---
# calit-6b9k
title: GOOGLE_MEET styling ignores the fallback location
status: todo
type: bug
priority: low
created_at: 2026-10-17T11:38:45Z
updated_at: 2026-10-17T11:38:45Z
---

Since calit-5fw0 a GOOGLE_MEET type with no minted link falls back to locationDetail, but EmailService.isMeet (email/_location.html) and PublicResource/confirmation.html:26-27 still style by locationType: a non-URL detail ("call me") renders as a Meet <a href>. Also confirmation.html lacks an {#if location} guard so a Meet type with no link and no detail shows an empty Meet: label (ADR 0005 says never empty). Key styling on an actual http(s) URL.
