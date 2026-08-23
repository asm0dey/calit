# Issue tracker: beans

Issues for this repo are **beans** — markdown files in `.beans/`, managed by the `beans` CLI.
Never hand-edit a bean file; always go through the CLI so ids, etags, and relationships stay valid.

GitHub Issues on `asm0dey/calit` still exist and are referenced by number (e.g. "#128"),
but they are inbound reports, not the working tracker. Work is filed as beans.

## Conventions

- One bean per unit of work. Types: `milestone`, `epic`, `bug`, `feature`, `task`.
- Statuses: `draft`, `todo`, `in-progress`, `completed`, `scrapped`.
  Priorities: `critical`, `high`, `normal`, `low`, `deferred`.
- Hierarchy via `--parent`; dependencies via `--blocked-by` / `--blocking`.
- A bean carries its own todo list in the body; check items off (`- [ ]` → `- [x]`) as work lands.
- On completion add a `## Summary of Changes` section; on scrapping add `## Reasons for Scrapping`.
- Bean files are committed alongside the code change they describe.

## When a skill says "publish to the issue tracker"

```bash
beans create --json "Title" -t <type> -d "<body>" -s todo [-p <priority>] [--parent <id>]
```

For a set of related tickets: create an `epic` first, then the children with `--parent <epic-id>`.

## When a skill says "fetch the relevant ticket"

```bash
beans show --json <id> [id...]          # full bean(s)
beans list --json -S "<search>"         # find by full-text search
beans list --json --ready               # actionable, unblocked work
beans query --json '<graphql>'          # advanced: traverse relationships, select fields
```

## When a skill says "comment on / update a ticket"

```bash
beans update --json <id> --body-append "## Notes\n\n..."
beans update --json <id> --body-replace-old "- [ ] Task" --body-replace-new "- [x] Task"
beans update --json <id> -s in-progress
```

Use `--if-match "$(beans show <id> --etag-only)"` when a concurrent writer is possible.

## Triage

The `triage` skill is not installed in this repo, so there is no label vocabulary and no
`docs/agents/triage-labels.md`. A bean's `status` and `priority` carry that state instead.
