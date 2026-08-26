---
# calit-gabg
title: Harden the container images and bump the NIK base
status: todo
type: task
priority: normal
created_at: 2026-08-26T12:04:50Z
updated_at: 2026-08-26T12:04:50Z
---

Container-image hardening, independent of any feature. Surfaced while designing calit-o89d (social preview images).

## Available now

- `Dockerfile.native` pins NIK `jdk-25-nik-25.0.3-musl`; `25.0.4` is published. Renovate pins digests but this is a tag bump.
- No NIK exists for JDK 26, so `Dockerfile.native` builds Java 25 while `Dockerfile` runs Java 26. Cannot be closed until BellSoft ships one — worth a comment in the file so the skew reads as known, not accidental.

## Findings on "hardened base"

- BellSoft publishes **no** hardened image. `bellsoft/alpaquita-linux-base` has 4 tags (latest, stream, musl, stream-musl); no tag matching "hardened" in any of the three BellSoft repos.
- A `hardened-release` package appears in the apk index, but `apk add hardened-release` fails from the configured stream repos and leaves `/etc/apk/repositories` unchanged. Looks like a stream that is not publicly reachable.
- Chainguard (`static`, `wolfi-base`) and Google distroless are **glibc**. The native binary is musl-linked and will not start on them. Switching would mean moving the whole native build to a glibc NIK image.
- `scratch` + `--static --libc=musl` is **incompatible with in-process image rendering**: AWT is dlopen-based (libawt.so, libfontmanager.so) and a static binary cannot load them. Choosing scratch means giving up the rendered social card (calit-o89d phase 2).

## Todo

- [ ] Bump NIK 25.0.3 -> 25.0.4 (or let Renovate, and verify the native smoke still passes)
- [ ] Comment the Java 25/26 skew in Dockerfile.native so it is not read as an oversight
- [ ] Read-only root filesystem + tmpfs /tmp, documented for compose/k8s users. NOTE: /tmp must be writable — Font.createFont(InputStream) spills to a temp file, so a read-only FS with no tmpfs breaks card rendering
- [ ] no-new-privileges, dropped capabilities, documented run flags
- [ ] Decide whether the glibc + Chainguard/distroless migration is worth a spike, given it trades away AWT rendering unless the binary stays dynamic
- [ ] Note that calit-o89d adds freetype + fontconfig (C font parsers, CVE-rich) to the runtime image; they only parse fonts we ship, but they widen the patch surface
