---
title: Customising the legal pages
description: Replace the shipped /privacy and /terms bodies with your own HTML with PRIVACY_POLICY_PATH and TERMS_PATH.
---

## You probably do not need this

calit serves a complete privacy policy at `/privacy` and terms at `/terms` with no configuration.
The policy describes what your deployment actually runs: no Google sections without Google, your
real retention window, and only the recipients you use. Set `OPERATOR_NAME` and
`PRIVACY_CONTACT_EMAIL` and the shipped pages name you as the controller.

Replace them only when your legal position differs from what the software describes.

## What the file is

`PRIVACY_POLICY_PATH` and `TERMS_PATH` each point at an HTML **fragment**: no `<!DOCTYPE>`,
`<html>`, `<head>` or `<body>`. calit serves it inside its normal layout, so it keeps the site
navigation and footer. Stick to the classes the shipped pages already use: the stylesheet is
compiled ahead of time and contains only classes calit itself references.

## It is not a template

The file is inserted as it is. calit does not parse it as a template, so `{inject:site.*}`,
`{msg:…}` and every other expression stay literal text, and a stray `{` does no harm. Write your
operator name and contact address directly into the file.

## Where to start

Start from what your own instance already serves, not from a copy on this site; a copy here would
drift away from the software. Run this **before** you set the variables, while the shipped page is
still being served:

```bash
curl -s "$APP_BASE_URL/privacy" \
  | sed -n '/<!-- CALIT_LEGAL_PRIVACY -->/,/<\/div>/p' \
  | sed '1d;$d' > /srv/calit/legal/privacy.html
```

That gives you your deployment's own rendered policy, with Google sections only if you use Google
and your real retention window, ready to edit. The second `sed` drops the marker comment and the
closing `</div>` of the page wrapper. `/terms` works the same way with the
`<!-- CALIT_LEGAL_TERMS -->` marker.

## Mounting the files with Docker

The path is read **inside the container**, so a host directory needs a bind mount. Read-only is
enough; calit never writes these files.

```yaml
services:
  calit:
    image: ghcr.io/asm0dey/calit:latest
    environment:
      PRIVACY_POLICY_PATH: /etc/calit/legal/privacy.html
      TERMS_PATH: /etc/calit/legal/terms.html
    volumes:
      - /srv/calit/legal:/etc/calit/legal:ro
```

Running the jar directly? Point the variables at any absolute path the process can read. A relative
path resolves against the working directory, which is rarely what you meant.

## Edits are live

calit reads the file on every request, so a change shows up on the next page load, with no restart.
The file must therefore stay readable. If it disappears or its permissions break, calit logs a
warning and serves the shipped page instead of failing: `/privacy` is linked from the Google OAuth
consent screen and must not go down. Watch your logs for
`Could not read legal fragment`.

## The path is trusted configuration

Treat these variables like your database password. Whatever file they point at is served to the
public, unescaped, so never point them at a file you did not mean to publish, or at one other people
can write to.

## Several replicas

Every replica reads its own copy of the path. Mount the same file into all of them, or they will
serve different policies.

See [Configuration](/calit/installation/configuration/#privacy) for the full variable reference.
