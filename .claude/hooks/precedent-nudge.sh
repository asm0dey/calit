#!/usr/bin/env bash
# An ADR, a design spec or CONTEXT.md is where a technical choice gets settled in THIS repo.
# The precedent graph is the same choice remembered across every repo, so a write to one is the
# cheapest possible moment to mirror it into the other. Silent for every other path.
set -uo pipefail

path=$(python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input", {}).get("file_path", ""))
except Exception: print("")' 2>/dev/null) || exit 0

case "$path" in
  */docs/adr/*.md|*/docs/superpowers/specs/*.md|*/CONTEXT.md) ;;
  *) exit 0 ;;
esac

script=$(ls -1d "$HOME"/.claude/plugins/cache/precedent/precedent/*/scripts/precedent.py 2>/dev/null | sort -V | tail -1)
[ -n "$script" ] || exit 0

python3 - "$path" "$script" <<'PY'
import json, sys
path, script = sys.argv[1], sys.argv[2]
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext":
    f"You just wrote {path}. If it settles a technical choice that is not already in the precedent "
    f"graph, mirror it there before this turn ends — the in-repo document holds the why for this "
    f"project, the graph holds it for the next one.\n"
    f"  1. uv run {script} check --topic <topic> --chose <option>   # exact-match; avoids a duplicate\n"
    f"  2. uv run {script} record --title ... --scope architecture|process|tooling|business|product "
    f"--topic ... --chose ... --rejected ... --statement ... --rationale ...\n"
    f"Record the rejected alternatives and the why — that is the half you will want quoted back. "
    f"Skip silently when the file only restates a recorded decision, records no choice, or the "
    f"choice is a mechanical call you made yourself rather than one the user settled."}}))
PY
