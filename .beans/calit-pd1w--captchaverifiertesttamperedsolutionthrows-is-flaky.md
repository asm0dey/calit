---
# calit-pd1w
title: CaptchaVerifierTest.tamperedSolutionThrows is flaky — base64 padding bits
status: in-progress
type: bug
priority: high
created_at: 2026-08-21T19:41:47Z
updated_at: 2026-08-21T19:47:34Z
---

`CaptchaVerifierTest.tamperedSolutionThrows` fails intermittently on any branch, reddening CI at random.

The test corrupts an Altcha payload by flipping its last base64 character:

```java
var bad = payload.substring(0, payload.length() - 2) + (payload.endsWith("A=") ? "B=" : "A=");
```

The payload ends in a single `=`, i.e. a group encoding 2 bytes into 3 characters — **the final character's low bits are padding and decode to nothing.** So flipping it changes the decoded bytes only some of the time. When it does not, the payload is byte-identical to the valid one, verification correctly succeeds, and the expected `AbuseException` never arrives:

```
CaptchaVerifierTest.tamperedSolutionThrows:71
  Expected site.asm0dey.calit.booking.AbuseException to be thrown, but nothing was thrown.
```

The payload is randomly generated per run (challenge, salt, signature), so which case you get is luck. Demonstrated in one sitting: the same test failed on `origin/main` and passed on `deps/2026-08-21-batch` locally, having failed on CI for that same batch minutes earlier.

Not a captcha defect — verification behaves correctly in both cases. The test's corruption method is simply unreliable.

Found while merging the batched dependency upgrades ([[calit-bh5t]] session); it blocked that batch's CI and briefly looked like a Quarkus 3.38.3 regression.

- [ ] Corrupt the payload in a way that always changes the decoded bytes — decode, flip a byte in the signature, re-encode; or flip a character well inside the string rather than in the final quantum
- [ ] Assert the corruption actually changed the decoded bytes before asserting the throw, so the test can never silently test nothing
- [ ] Check the sibling captcha tests for the same trick
