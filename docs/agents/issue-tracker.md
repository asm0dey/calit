# Issue tracker: GitHub Issues

Issues for this repo are **GitHub Issues** on `asm0dey/calit`, managed with the `gh` CLI. Refer to them by
number (`#128`). They replaced the `beans` tracker; the completed and scrapped beans live on in git history
under `.beans/` (last present before the migration commit), and each migrated issue ends with a
"Migrated from bean `calit-xxxx`" footer.

## Labels

- **Type**, exactly one: `bug`, `enhancement` (a feature), `task` (engineering work that is neither),
  `epic` (groups related issues as sub-issues).
- **State**: `draft` (an idea not yet refined into actionable work), `in progress`. No label means ready to do.
- **Priority**: `priority: high`, `priority: low`. No label means normal.
- **Topic**: e.g. `competitor-parity`, `dependencies`, `documentation`.

## Conventions

- One issue per unit of work. An issue carries its own todo list in the body; tick items
  (`- [ ]` → `- [x]`) as work lands.
- Hierarchy: make the child a **sub-issue** of its epic or parent feature.
- Dependencies: end the body with `Blocked by #N.`
- A PR closes its issue with `Closes #N` in the PR body.
- Drop an issue with `gh issue close <n> --reason "not planned" --comment "<why>"`.

## When a skill says "publish to the issue tracker"

```bash
gh issue create --title "Title" --body-file body.md --label task [--label "priority: low"]
```

For a set of related tickets: create the `epic` first, then each child, then link it:

```bash
child=$(gh api repos/asm0dey/calit/issues/<child-number> --jq .id)
gh api -X POST repos/asm0dey/calit/issues/<epic-number>/sub_issues -F sub_issue_id="$child"
```

## When a skill says "fetch the relevant ticket"

```bash
gh issue view <n> [--comments]                      # one issue
gh issue list --search "<text>" --state open        # full-text search
gh issue list --label bug --label "priority: high"  # filter by labels
gh api repos/asm0dey/calit/issues/<n>/sub_issues    # children of an epic
```

## When a skill says "comment on / update a ticket"

```bash
gh issue comment <n> --body "..."
gh issue edit <n> --add-label "in progress"
gh issue edit <n> --body-file body.md      # e.g. after ticking a todo item; read the body first with gh issue view
```

## Triage

The `triage` skill is not installed in this repo. The type, state and priority labels above carry triage
state; there is no `docs/agents/triage-labels.md`.
