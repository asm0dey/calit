---
title: Language & localization
description: calit supports English, German, and Hebrew out of the box — for both booking visitors and account owners.
---

calit ships with English, German, and Hebrew built in. No configuration or environment variables are required to enable any of them — all are always available.

Hebrew (`עברית`) is a right-to-left language: when it is active, calit automatically mirrors the entire layout (`<html dir="rtl">`) for both web pages and emails. No setting controls this — it follows the chosen language.

If a phrase is not yet translated, calit falls back to English automatically.

## For booking visitors (invitees)

When someone opens your public booking page (`/<username>/<slug>`), calit detects their preferred language from the browser's `Accept-Language` header and displays the page accordingly.

### Changing the language

A **language switcher in the page footer** lets visitors choose between **English**, **Deutsch**, and **עברית**. Selecting a language:

- Reloads the page in the chosen language immediately.
- Saves the choice in a `calit_lang` cookie so it persists across page loads and future visits.

### Email language

The language a visitor uses when booking is remembered for that booking. Any follow-up emails they receive — confirmation, reminder, cancellation, reschedule, and declined notices — are sent in that same language.

## For account owners

Account owners control their own language independently of any visitor's browser setting. The language choice applies to:

- The owner's admin UI (`/me` and all sub-pages).
- Notification emails the owner receives (new booking alerts, etc.).

### Changing the language

Go to **Settings** (accessible from the `/me` navigation) and find the **Language** field. Choose **English**, **Deutsch**, or **עברית** and save. The change takes effect immediately.

## Adding more languages

Community contributions are welcome — adding a new language requires dropping two translation files (`msg_XX.properties` and `adm_XX.properties`) into `src/main/resources/messages/`; the language is then auto-discovered with no further configuration. Right-to-left languages need no extra work — the `dir` attribute is derived from the active language. See the project repository for details.
