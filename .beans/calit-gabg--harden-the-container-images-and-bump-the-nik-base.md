---
# calit-gabg
title: Harden the container images and bump the NIK base
status: todo
type: task
priority: normal
created_at: 2026-08-26T12:04:50Z
updated_at: 2026-08-26T12:09:46Z
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

## Correction: hardened images DO exist

The "Findings" section above searched only the three repos already referenced in the Dockerfiles and
concluded no hardened image existed. Wrong. There are dedicated repos:

- `bellsoft/hardened-liberica-runtime-container:jre-distroless-musl` — musl, JDK 26.0.2.1, 130.8 MB,
  no shell, no apk. Also `jre-26-nonroot-musl`, likewise without apk.
- `bellsoft/hardened-liberica-native-image-kit-container:jdk-25-nik-25.0.4-musl` — hardened builder,
  with nonroot/distroless variants. Still JDK 25; no NIK for JDK 26 exists at all, so the Java 25/26
  skew is upstream, not ours.
- No hardened *bare* base. Every hardened tag carries a JRE/JDK, so `Dockerfile.native`'s 8.2 MB
  Alpaquita final layer has no hardened equivalent — a native binary in a 130 MB JRE image would be a
  large regression.

### Measured, for the font rendering calit-o89d needs

| Base | Size | Renders |
|---|---|---|
| `liberica-runtime-container:jre-26-musl` (today) | 138.0 MB | no — missing libfreetype.so.6 |
| `hardened-...:jre-distroless-musl` | 130.8 MB | no — same |
| hardened distroless + font stack copied from a builder stage | 134.2 MB | **yes, verified** |

Moving the JVM image to hardened distroless *and* fixing font rendering lands 3.8 MB **smaller** than
today's already-broken base. These images have no package manager, so the font stack must be COPYed
from a builder stage: libfreetype.so.6, libfontconfig.so.1, libexpat, libbz2, libpng16, libbrotlidec,
libbrotlicommon, /etc/fonts, the font files, and /var/cache/fontconfig with fc-cache run in the builder.

## Revised todo

- [ ] JVM image -> `hardened-liberica-runtime-container:jre-distroless-musl` + COPYed font stack
- [ ] Native builder -> `hardened-liberica-native-image-kit-container:jdk-25-nik-25.0.4-musl`
- [ ] Native final layer stays Alpaquita; revisit if BellSoft ever ships a hardened bare base
- [ ] Distroless has no shell: any CI or debug step that execs into the container must probe from outside instead
