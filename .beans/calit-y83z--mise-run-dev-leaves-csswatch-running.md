---
# calit-y83z
title: mise run dev leaves css:watch running
status: completed
type: bug
priority: normal
created_at: 2026-09-29T21:55:05Z
updated_at: 2026-09-29T22:02:01Z
---

Actual cause: css:watch as a background job in sh gets /dev/null stdin, and Tailwind v4 --watch exits on stdin EOF — so mise run dev never watched at all. With --watch=always it would outlive Quarkus (bun kill leaves the tailwind child), hence set -m + group kill.

- [x] stop watcher when dev exits (normal exit + Ctrl-C)
- [x] verify no leftover tailwind process

## Summary of Changes

css:watch now uses --watch=always (survives /dev/null stdin); mise dev runs it in its own process group (set -m) and kills that group on EXIT/INT/TERM. Verified in a tmux pty: rebuild on change, zero tailwind processes after Ctrl-C.
