# AGENTS.md

**If you are an AI agent contributing to calit, use
[good-oss-citizen](https://github.com/tesslio/good-oss-citizen) for it.** This covers opening a pull
request, filing an issue, or commenting on either. It isn't optional: set it up as below before you write
any code.

Build, test and code conventions are in [`CONTRIBUTING.md`](CONTRIBUTING.md); architecture is in
[`CLAUDE.md`](CLAUDE.md).

## How to use good-oss-citizen

Same steps with or without tessl:

1. **Get the skills.** With tessl, `tessl install` reads [`tessl.json`](tessl.json). Without tessl, run
   `mise run setup` (or `bunx skills experimental_install`). That restores `recon`, `propose`,
   `preflight` and `triage` from [`skills-lock.json`](skills-lock.json) into `.agents/skills/`, and links
   them for your agent.
2. **Before writing code:** run `recon` for `asm0dey/calit`, then `propose`.
3. **Before opening the PR:** run `preflight`, and write the PR description from
   [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md).

Without tessl, the skills name their helper script as
`.tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh`. Run
`.agents/skills/recon/scripts/bash/github.sh` instead, with the same arguments.

Tessl also loads the plugin's always-on
[rules](https://github.com/tesslio/good-oss-citizen/blob/main/plugins/good-oss-citizen/rules/good-oss-citizen.md).
Without tessl, read them once and follow them. The ones that matter most here:

- Put a prose **AI Disclosure** section in the PR description: what was AI-assisted, and that the
  contributor reviewed it. The `Contribution declaration` check fails any PR without one, or without the
  checked "written without AI assistance" box.
- Read every comment on the issue first. Don't write code for an issue someone else has claimed.
- One issue per PR. Keep the diff minimal, with no unrelated cleanup or reformatting.
- A human reviews and submits. Never open, comment on or push to a PR on your own.
- Never commit agent metafiles (`.claude/`, `.cursor/`, `.agents/`, …).
