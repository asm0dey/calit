---
title: Quick start
description: Run calit with Docker Compose and create your first admin user.
---

This page walks you through the shortest path to a running calit instance.

## Prerequisites

- **Docker and Docker Compose** installed on the host.
- A **public HTTPS URL** pointing to the host (required for production use, e.g. `https://book.example.com`). For a local trial, `http://localhost:8080` works.
- An **SMTP server** for sending booking confirmation and reminder emails.

## Steps

### 1. Get the files

You don't need to clone or build anything — calit runs from the prebuilt image on GitHub Container
Registry. In an empty directory, grab the two config files:

```bash
curl -O https://raw.githubusercontent.com/asm0dey/calit/main/docker-compose.yml
curl -O https://raw.githubusercontent.com/asm0dey/calit/main/.env.example
```

### 2. Create your `.env`

```bash
cp .env.example .env
```

Open `.env` and set at minimum:

| Variable | Description |
|---|---|
| `DB_PASSWORD` | Password for the Postgres database. |
| `APP_BASE_URL` | Public origin of the app, e.g. `https://book.example.com`. |
| `SESSION_ENCRYPTION_KEY` | At least 16 characters. Generate with `openssl rand -hex 32`. |
| `TOKEN_ENCRYPTION_KEY` | Exactly 64 hex characters. Generate with `openssl rand -hex 32`. |
| `MAIL_HOST` | SMTP hostname. |
| `MAIL_PORT` | SMTP port (commonly `587` or `465`). |
| `MAIL_USERNAME` | SMTP username. |
| `MAIL_PASSWORD` | SMTP password. |
| `MAIL_FROM` | From address for outgoing mail. |
| `MAIL_START_TLS` | STARTTLS mode: `REQUIRED`, `OPTIONAL`, or `DISABLED`. Use `REQUIRED` with port 587. |
| `MAIL_TLS` | `true` to use implicit TLS (port 465); `false` otherwise. |

:::tip[Generate secure keys]
Run `openssl rand -hex 32` twice — once for `SESSION_ENCRYPTION_KEY`, once for `TOKEN_ENCRYPTION_KEY`. Store both securely; all replicas must share the same values.
:::

### 3. Start

```bash
docker compose up -d
```

This pulls the prebuilt image and starts two containers: `app` (Quarkus on port 8080) and `db` (Postgres).

### 4. Create the first admin user

Open `APP_BASE_URL` in your browser. Because the database is empty, the app redirects automatically to `/setup`. Fill in the form to create your first admin account. There is no default password.

:::note
The `/setup` route returns 404 once any user exists. It cannot be used to create additional users after initial setup.
:::

### 5. Done

You are now logged in as the admin. From the management UI at `/me` you can:

- Create meeting types with custom slugs, durations, and booking controls.
- Configure your availability and calendar integrations.
- Invite additional users (if you enable sign-ups).

---

## Next steps

- [Configuration reference](/calit/installation/configuration/) — all environment variables explained.
- [Reverse proxy setup](/calit/installation/reverse-proxy/overview/) — put Nginx or Caddy in front of calit.
- [First-run walkthrough](/calit/usage/first-run/) — a guided tour of the initial setup wizard.
