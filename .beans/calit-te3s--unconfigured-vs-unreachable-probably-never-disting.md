---
# calit-te3s
title: UNCONFIGURED vs UNREACHABLE probably never distinguishes in production
status: todo
type: bug
priority: normal
created_at: 2026-09-11T07:34:48Z
updated_at: 2026-09-11T07:34:48Z
---

Follow-up from #195 (PR #207), raised by the final whole-branch review. Pre-existing in SmtpHealthCheck; #195 is what puts it in front of users.

SmtpHealthCheck reports `mocked-or-unconfigured` only when quarkus.mailer.mock is true or quarkus.mailer.host is ABSENT. The Quarkus mailer extension defaults host to localhost, so a production deployment that simply forgot MAIL_HOST has a host, fails to connect, and shows the *unreachable* banner — "Email is not being delivered" — when the truthful message is "Email is not configured". That is precisely the distinction the dashboard banner was added to make.

Second, smaller: SmtpHealthCheck probes port 587 by default while the mailer's own default port is 25, so the probe can disagree with what the mailer would actually do.

- [ ] Treat a host equal to the mailer's own default (localhost) with no explicit MAIL_HOST as unconfigured
- [ ] Align the probe's default port with the mailer's, or read the mailer's effective port
