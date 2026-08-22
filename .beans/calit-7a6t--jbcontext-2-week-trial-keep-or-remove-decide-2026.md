---
# calit-7a6t
title: 'jbcontext 2-week trial: keep or remove (decide 2026-09-05)'
status: in-progress
type: task
created_at: 2026-08-22T09:29:52Z
updated_at: 2026-08-22T09:29:52Z
---

Trial of JetBrains Context (`jbcontext` 0.9.9, build 592) as a semantic-search layer for this repo. Started 2026-08-22. Decide keep-or-remove on 2026-09-05.

Prior art: `ai-codeindex` was evaluated and REMOVED the same day (static local symbol dump; skipped every non-Java file). Upstream report: https://github.com/dreamlx/codeindex/issues/189

## Why it might pay off

Measured 3 seam queries against grep on 2026-08-22, 2 wins / 1 miss:

- "which template renders the meeting type create form" -> `templates/AdminResource/meetingTypes.html` rank 1 (sim 1.000). WIN, grep-hard.
- "migration that adds the google calendar id column to booking" -> `V26__booking_calendar_address.sql` rank 1. WIN, but soft (grep on the column name also finds it in one shot).
- "German and Hebrew translation for the add frame button" -> MISS. `.properties` files are NOT indexed: exact key `adm_detail_frame_add` returns only the Java bundle, and `-p messages` returns 0 results, while `grep -rl` finds it in both locale files.

The miss matters: CLAUDE.md makes de+he parity mandatory on every user-facing string, so that lookup is the most repetitive one in this repo.

## Baseline (2026-08-22, `jbcontext analyze --agent claude`)

- Exploration = 31% of the average task, ~236 sec reading & searching code
- jbcontext usage 0% -> 1% (only this session's 4 searches)
- With/without comparison: 102 tasks without, 1 with — UNPAIRED, no signal
- Modeled eval estimate for this task mix: ~10% lower cost, ~14% fewer tokens, ~9% fewer calls
- NOTE: the -86% tokens/task column in that run is model/task-mix change, NOT jbcontext (`jbcontext use 0.0 pts (0%->0%)` in the same cohort). Do not read it as an effect.

## Decision criteria for 2026-09-05

Keep only if BOTH hold:

- `jbcontext analyze --agent claude --details` shows meaningfully more than a handful of paired with/without tasks — i.e. the tool was actually used, not just installed.
- Reviewing real usage, semantic search beat grep on questions where grep was awkward. Winning on lookups grep would also have nailed is not a win.

Remove if: usage stayed near 0%, or the `.properties` blind spot kept sending searches back to grep anyway.

## Trial artifacts (all UNCOMMITTED — do not land in a feature PR)

Installed via `jbcontext setup-agent --agent CLAUDE --auto --scope project`:

- `CLAUDE.md` (TRACKED, modified) — ~90-line instruction block between `<!-- jbcontext-instructions-start -->` / `<!-- jbcontext-instructions-end -->`
- `.claude/settings.json` (TRACKED, modified) — SessionStart + SessionEnd `jbcontext index --silent`
- `.mcp.json` (TRACKED, modified) — jbcontext MCP server entry
- `.claude/agents/context-explorer.md` (new, untracked)
- `.claude/skills/context-search/` (new; note `.claude/skills/` is otherwise a gitignored symlink farm managed by `bunx skills experimental_install`)
- Remote index snapshot on the JetBrains AI Platform for `github.com/asm0dey/calit`

Three of those are tracked files. Stage explicit paths when committing feature work so the trial never rides along.

## Removal (if the verdict is remove)

- [ ] `jbcontext remove-agent --agent CLAUDE --scope project`
- [ ] `jbcontext remove-index`
- [ ] `git checkout -- CLAUDE.md .claude/settings.json .mcp.json` (confirm no real changes are mixed in first)
- [ ] Drop the SessionStart hook from `.claude/settings.local.json` if it is still there
- [ ] Send findings upstream via `jbcontext send-feedback` — especially the `.properties` gap

## Todo

- [ ] Run `jbcontext setup-agent --agent CLAUDE --auto --scope project --non-interactive` (BLOCKED: auto-mode classifier denies config writes; user must run it)
- [ ] After install: remove the duplicate SessionStart hook from `.claude/settings.local.json` (setup-agent adds its own to settings.json — otherwise 3 index runs per session)
- [ ] After install: check the `.mcp.json` entry uses an absolute binary path — `jbcontext` is not on PATH outside fish (installer only wrote `~/.config/fish/conf.d/jbcontext.fish`)
- [ ] 2026-09-05: run `jbcontext analyze --agent claude --details`, apply the criteria above, decide
