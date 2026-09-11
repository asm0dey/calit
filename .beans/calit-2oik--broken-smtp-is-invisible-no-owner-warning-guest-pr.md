---
# calit-2oik
title: 'Broken SMTP is invisible: no owner warning, guest promised an email that never arrives'
status: in-progress
type: feature
priority: high
created_at: 2026-09-10T22:14:57Z
updated_at: 2026-09-11T06:20:35Z
---

GH #195. A deployment with broken/unconfigured SMTP looks healthy: dashboard says nothing, copy-link toast is unconditionally green, the guest confirmation page unconditionally promises a confirmation email, and a failed send takes the .ics with it (no endpoint serves it). Plan: docs/superpowers/plans/2026-09-11-smtp-delivery-visibility.md
