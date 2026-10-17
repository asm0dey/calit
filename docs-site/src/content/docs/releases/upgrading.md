---
title: Upgrading
description: How to upgrade a running calit instance safely.
---

## Before you upgrade

**Back up your Postgres database.** Flyway migrations apply automatically at
boot; a backup lets you roll back if something goes wrong.

```bash
docker exec <postgres-container> pg_dump -U calit calit > calit-backup.sql
```

## Upgrade procedure

### Prebuilt image (recommended)

Pull the new image and restart the stack:

```bash
docker compose pull
docker compose up -d
```

### Build from source

```bash
docker compose up --build -d
```

In both cases, calit applies any new Flyway migrations (`V1..Vn`) automatically
on startup. Hibernate is configured in validate-only mode — it never modifies
the schema; migrations own it entirely.

## Secret keys — keep them stable

Two environment variables **must not change** across upgrades:

| Variable | Effect of rotation |
|---|---|
| `SESSION_ENCRYPTION_KEY` | All active sessions are invalidated; users are logged out. |
| `TOKEN_ENCRYPTION_KEY` | Encrypted Google OAuth tokens become unreadable; every user must reconnect their Google account. |

Rotating either key is safe from a security standpoint, but `TOKEN_ENCRYPTION_KEY`
rotation is destructive for connected Google accounts. Only rotate it if you
have a deliberate reason (e.g., suspected key compromise) and are prepared to
ask all users to reconnect.

See [Configuration](/calit/installation/configuration/) for the full list of
environment variables.

## Rolling restarts

calit is stateless — all shared state lives in Postgres. If you run multiple
replicas, a rolling restart (bringing replicas up one at a time) is safe.
Run Flyway migrations before the rolling restart by letting the first replica
start fully before updating the rest, or run migrations as a separate init
step.

CalDAV caveat: the previous release (1.27.x) cannot see CalDAV accounts. Don't connect one until every
replica runs the new release, and don't roll back to 1.27.x once one is connected.

## Further reading

- [Docker Compose deployment](/calit/installation/docker-compose/)
- [Configuration reference](/calit/installation/configuration/)
