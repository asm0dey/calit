---
# calit-dmcn
title: CaptchaVerifierTurnstileTest hardcodes port 18477
status: completed
type: task
priority: low
created_at: 2026-09-12T15:59:37Z
updated_at: 2026-09-12T16:06:07Z
---

`CaptchaVerifierTurnstileTest` binds its JDK `HttpServer` stub to a hardcoded port (18477) instead of an ephemeral one. It is the original of the pattern: the outbound-notification-channels plan copied it, and four notify/scheduler tests inherited hardcoded ports before being converted on that branch.

A hardcoded port collides with whatever else is listening on the box, breaks if two suites ever run concurrently, and makes every new stub test pick a magic number by hand.

Fix is three lines:

```java
server = HttpServer.create(new InetSocketAddress(0), 0);
server.start();
port = server.getAddress().getPort();
```

then derive the verify URL from `port` rather than the constant.

Left out of the outbound-notifications branch deliberately: it predates that work and is unrelated to notification channels, so touching it would have widened a 9-task feature PR.

- [ ] Convert to an ephemeral port and derive `turnstileVerifyUrl` from it
- [x] Confirm no other test or fixture assumes 18477
- [x] Full suite green

## Summary of Changes

Converted as part of the outbound-notifications final-review fix wave: the stub now binds
`InetSocketAddress(0)`, `@BeforeAll` reads `server.getAddress().getPort()` back into a static
`port` field, and `turnstileVerifyUrl` is derived from it. `grep -rn 18477 src/` is clean. The
four notify/scheduler delivery tests moved to the same shape in the same commit, so no
hardcoded stub port is left in the repo. Full suite green: 1129 tests, 0 failures.
