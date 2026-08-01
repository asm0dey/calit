---
title: Docker Compose
description: Run calit and its Postgres with the provided compose file.
---

calit ships a `docker-compose.yml` with two services: `db` (Postgres 18) and `app` (the calit server). The app is stateless — all shared state lives in Postgres — so the same file works for a single node or a scaled-out cluster.

## The compose file

```yaml
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: ${DB_NAME:-calit}
      POSTGRES_USER: ${DB_USER:-calit}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    volumes:
      # postgres:18 default PGDATA is /var/lib/postgresql/18/docker — mount the parent so data persists
      - calit-db:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER:-calit} -d ${DB_NAME:-calit}"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped
  app:
    image: ghcr.io/asm0dey/calit:latest
    depends_on:
      db:
        condition: service_healthy
    env_file:
      - path: .env
        required: false
    environment:
      DB_URL: jdbc:postgresql://db:5432/${DB_NAME:-calit}
      DB_USER: ${DB_USER:-calit}
      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    ports:
      - "${APP_PORT:-8080}:8080"
    restart: unless-stopped
volumes:
  calit-db:
```

You don't need to clone the repository — save the file above as `docker-compose.yml`, grab
[`.env.example`](https://github.com/asm0dey/calit/blob/main/.env.example) next to it, and you're ready.

## Key details

| What | Why it matters |
|---|---|
| `image: ghcr.io/asm0dey/calit:latest` | Pulls the published multi-arch image from GitHub Container Registry — no local build. A [smaller native variant](#native-image-lower-footprint) and a [build-from-source option](#build-from-source) are below. |
| `depends_on: condition: service_healthy` | The app starts only after Postgres passes its `pg_isready` healthcheck, so Flyway migrations never race a cold DB. |
| `env_file: .env` | All secrets are loaded from `.env`. Marked `required: false` so `docker compose config` works on a fresh checkout without an `.env` file present. |
| `DB_URL` (derived) | `jdbc:postgresql://db:5432/${DB_NAME:-calit}` is injected automatically — your `.env` only needs to carry `DB_PASSWORD`. |
| `${APP_PORT:-8080}:8080` | The container always listens on 8080. Set `APP_PORT` in `.env` to expose a different host port. |
| Volume mount: `/var/lib/postgresql` | Postgres 18 changed its default `PGDATA` to `/var/lib/postgresql/18/docker`. Mounting the **parent directory** (`/var/lib/postgresql`) ensures data persists across container restarts regardless of the exact subdirectory used. |

## Native image (lower footprint)

Every published tag also has a GraalVM **native** counterpart with a `-native` suffix
(`:latest-native`, `:edge-native`, `:1.11.0-native`, …). It runs the same application
compiled ahead-of-time on a minimal Alpaquita musl base — no JRE.

| | `:latest` (JVM) | `:latest-native` |
| --- | --- | --- |
| Image size | ~205 MB | ~115 MB |
| Memory (idle) | ~300 MB | ~60 MB |
| Startup | ~2.4 s | ~0.4 s |

Use it by appending `-native` to the tag:

```yaml
  app:
    image: ghcr.io/asm0dey/calit:latest-native
```

The native image is functionally identical and multi-arch (amd64 + arm64). Its smaller
memory footprint suits small VPS hosts. The JVM image remains the default; pick whichever
fits your deployment.

## Build from source

Prefer to build the image yourself (e.g. running a fork or an unreleased commit)? Clone the
repository and swap the `app` service's `image:` line for `build: .`:

```yaml
  app:
    build: .
```

Then start with `docker compose up --build -d`. This is what the `docker-compose.yml` in the
repository does by default (Liberica JDK 26 build). For just running calit, the prebuilt image
above is faster and needs no build toolchain.

## Scaling

calit is stateless — scale the `app` service horizontally behind your own load balancer:

```bash
docker compose up -d --scale app=3
```

Postgres is the single shared state store; no additional coordination is needed between replicas.

## Starting up

1. Copy `.env.example` to `.env` and fill in your values — see the [Configuration](/calit/installation/configuration/) page for every variable.
2. Start the stack:

```bash
docker compose up -d
```

Compose pulls `ghcr.io/asm0dey/calit:latest` on first run. On first boot, Flyway applies all database migrations automatically. Navigate to your `APP_BASE_URL` — if no users exist yet, you will be redirected to `/setup` to create the first admin account.
