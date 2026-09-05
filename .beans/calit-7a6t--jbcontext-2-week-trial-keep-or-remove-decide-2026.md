---
# calit-7a6t
title: 'jbcontext 2-week trial: keep or remove (decide 2026-09-05)'
status: completed
type: task
priority: normal
created_at: 2026-08-22T09:29:52Z
updated_at: 2026-09-05T16:33:07Z
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

- [x] `jbcontext remove-agent --agent CLAUDE --scope project` — hooks, skills, subagent, instructions and MCP entry all removed
- [~] `jbcontext remove-index` — NOT run. It is an interactive y/N that deletes remote data on the JetBrains AI Platform; left for the user to confirm. Harmless to leave: nothing consults it now that the MCP server and instructions are gone.
- [x] ~~`git checkout -- CLAUDE.md .claude/settings.json .mcp.json`~~ — WRONG by the time it ran: the trial edits had long since been committed, so a checkout would have discarded nothing and reverted nothing. `remove-agent` did the surgical removal instead; verified by diff that only jbcontext entries went (`javadocs` MCP and the `beans prime` SessionStart hook untouched) and both JSON files still parse.
- [x] Drop the SessionStart hook from `.claude/settings.local.json` — none present; nothing to do.
- [~] Send findings upstream via `jbcontext send-feedback` — NOT sent (outward-facing; needs the user). Also largely moot: the `.properties` gap is FIXED. Re-tested 2026-09-05, `adm_de.properties` now returns at rank 2 for a natural-language translation query.

## Todo

- [x] Run `jbcontext setup-agent ...` — done during the trial (the config it wrote is what was just removed).
- [x] After install: remove the duplicate SessionStart hook — moot now, both hooks are gone.
- [x] After install: check the `.mcp.json` entry path — it stayed the bare `jbcontext` command; moot now, the entry is gone.
- [x] 2026-09-05: ran `jbcontext analyze --agent claude`, applied the criteria, decided REMOVE.

## Data point: 2026-08-22 bug-sweep planning

Wrote `docs/superpowers/plans/2026-08-22-bug-sweep.md` (8 bugs, ~30 file reads). **jbcontext calls: 0.**

Not a signal against the tool — a signal about the task shape. Every bug bean already
carried exact `file:line` pointers (`AdminResource.java:1131`, `PublicResource.java:542-550`,
`BookingService.java:1172-1178`), because whoever filed the bean had already done the
discovery. There was nothing to locate, only code to read. CLAUDE.md's own rule says skip
context-explorer when the task names the exact file, and that covered all 8 tasks.

Implication for the keep/remove decision: judge the trial on COLD-START tasks ("where does X
happen", an unfamiliar subsystem, a GH issue with no file named), not on planning from
well-filed beans. If most calit work starts from a bean with line numbers in it, the tool's
addressable surface is smaller than the trial assumed — which is itself an argument for
removing it, but on grounds of task mix, not tool quality.

## Verdict (2026-09-05): REMOVE

Decided on the trial's own criteria, which required BOTH to hold to keep. Criterion 1 failed.

### Criterion 1 — was it actually used? NO. This is the whole decision.

`jbcontext analyze --agent claude`, last 30 days, 116 of 526 tasks:

- **jbcontext usage 0% -> 5%.** After two weeks installed, with a CLAUDE.md rule making it the MANDATORY first code-discovery step, it was used in a twentieth of tasks.
- With-vs-without on this repo reports Tokens/task +217%, Cost/task +187% — i.e. tasks that used it cost MORE. Confounded (observational, "harness also changed", 73% comparable) so it is NOT evidence the tool is expensive. It is simply the absence of any measured benefit.
- The modeled eval estimate still projects ~10% lower cost / ~15% fewer tokens for this task mix. That projection never materialised in two weeks of real use.

### Criterion 2 — did semantic search beat grep where grep was awkward? UNPROVEN, for lack of usage.

Not enough real calls to judge. The 2026-08-22 spot-check was 2 wins / 1 miss, and the miss is now fixed (below). Criterion 2 is neither pass nor fail — there is no evidence either way, which is itself the problem.

### The `.properties` blind spot is FIXED — reporting it because it argues AGAINST removal

Re-tested today. `jbcontext search "German and Hebrew translation for the past overrides summary label"` returned:

1. `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`
2. `src/main/resources/messages/adm_de.properties`
3. a related bean

Locale files ARE indexed now, and the de/he parity lookup — the most repetitive query in this repo — works. The single strongest technical objection from 2026-08-22 no longer stands. The tool got better; it just did not get used.

### Why usage stayed at zero — the honest reason

Two causes, and only one is about the tool.

1. **Task mix, as the 2026-08-22 bug-sweep note predicted.** Most work here starts from a bean that already carries `file.java:1131` pointers, because whoever filed it did the discovery. Nothing to locate, only code to read. CLAUDE.md's own carve-out ("skip when the task names the exact file") then legitimately covers it.
2. **A rule conflict that silently disabled the mandated entry point.** CLAUDE.md ordered `Task(subagent_type='context-explorer')` as the FIRST code-discovery step. The session harness carries "Do not call the AgentTool unless the user requested it". Those contradict, the harness instruction won every time, and `context-explorer` was withdrawn mid-session on 2026-09-05 anyway. The mandated path was unreachable.

   The CLI needed no agent and was available throughout. It still went unused — so the conflict explains part of the zero, not all of it.

### The decisive data point: GH #168, today

A genuine cold-start task — a GitHub issue naming a feature ("date overrides") and no file. Exactly the shape this bean said to judge the trial on. **jbcontext calls: 0.** First discovery step was `grep -rln "DateOverride..."`, which landed the right files in one shot because the domain word and the class name coincide.

That is the trial's fair test, and the tool was not reached for even there.

## Summary of Changes

`jbcontext remove-agent --agent CLAUDE --scope project` removed, on branch `chore/remove-jbcontext`:

- `CLAUDE.md` — the ~85-line block between the `jbcontext-instructions` markers (context-explorer-first rule, single-shot policy, examples)
- `.claude/settings.json` — SessionStart and SessionEnd `jbcontext index --silent` hooks
- `.mcp.json` — the `jbcontext` stdio server entry
- `.claude/agents/context-explorer.md` — deleted
- `.claude/skills/context-search/` — deleted (gitignored symlink farm, so it does not appear in the diff)

206 deletions, 1 insertion, 4 files. Verified: zero residual `jbcontext` / `context-explorer` / `context-search` references outside `.beans/`; both JSON files still parse; the `javadocs` MCP server and the `beans prime` SessionStart hook are untouched.

Left for the user: `jbcontext remove-index` (interactive, deletes remote data) and `jbcontext send-feedback`.

## If this is ever revisited

Re-run the trial only on a stretch of cold-start work, and fix the rule conflict first — mandate the `jbcontext search` CLI directly rather than an agent wrapper that the harness may forbid or remove. The `.properties` gap is closed, so the tool would start from a better position than it did in August.
