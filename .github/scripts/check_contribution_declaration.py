#!/usr/bin/env python3
"""Contribution-declaration gate check.

Reads a pull-request body and decides whether it carries an explicit
contribution declaration — one of:

  * an **AI Disclosure** section with non-empty content (the artifact
    good-oss-citizen writes into every PR it prepares), OR
  * an explicit **no-AI** declaration (a checked "written without AI
    assistance" box, or a freehand statement to the same effect).

If neither is present the PR is undeclared and the gate fails. The check
is deliberately framed around the *outcome* (AI use is disclosed, or the
contributor declares none) — it does not, and cannot, prove that any
particular tool ran. A careful human who writes a disclosure by hand
passes, by design.

This file is vendored verbatim into consumer repositories by the
`install-gate` skill and run from their CI, so it is intentionally
self-contained: standard library only, no local imports.

Input (first match wins):
  --body-file PATH   read the PR body from a file
  env PR_BODY        read the PR body from the environment variable
  stdin              read the PR body from stdin

Output: exactly one JSON envelope on stdout:
  {
    "command":  "check-contribution-declaration",
    "ok":       <bool>,        # the check ran and produced a verdict
    "data":     {
      "verdict":            "pass" | "fail",
      "ai_disclosure":      <bool>,
      "no_ai_declaration":  <bool>,
      "reasons":            [<str>, ...]   # human-readable, drives the PR comment
    },
    "warnings": [<str>, ...],
    "errors":   [<str>, ...]
  }

Exit code (so the gate can drive a CI check directly):
  0  verdict == pass
  1  verdict == fail
  2  internal error (ok == false; could not read or parse input)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

COMMAND = "check-contribution-declaration"

# Normalized phrases that signal an explicit "no AI was used" declaration.
# Matched after normalize() collapses separators, so "without-AI" and
# "without AI" both reduce to "without ai". Each phrase carries an explicit
# negator ("no"/"not"/"without") so an AI-assisted disclosure ("prepared
# with AI assistance") never matches.
NO_AI_PHRASES = (
    "without ai assistance",
    "without the use of ai",
    "without using ai",
    "without ai help",
    "without ai tools",
    "no ai assistance",
    "no ai was used",
    "no ai tools were used",
    "no use of ai",
    "not ai assisted",
    "not ai generated",
    "written without ai",
)

HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(?P<text>.*?)\s*#*\s*$")

# An "AI Disclosure" label, in any of the three structural forms the plugin
# (or a careful human) writes it: a markdown heading, a bold lead-in with the
# colon inside the bold markers (`**AI Disclosure:**`), or a plain label that
# ends in a colon. A bare prose mention ("AI Disclosure is mandatory") is not
# structural and is deliberately not matched. `rest` is the inline remainder.
DISCLOSURE_MARKERS = (
    re.compile(
        r"^\s{0,3}#{1,6}\s+\**\s*ai\s+disclosure\b\s*\**\s*:?\s*(?P<rest>.*)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s{0,3}\*\*\s*ai\s+disclosure\b\s*:?\s*\*\*\s*:?\s*(?P<rest>.*)$",
        re.IGNORECASE,
    ),
    re.compile(r"^\s{0,3}ai\s+disclosure\s*:\s*(?P<rest>.*)$", re.IGNORECASE),
)
CHECKBOX_RE = re.compile(r"^\s*[-*]\s+\[(?P<mark>[ xX])\]\s*(?P<label>.*)$")
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
FENCE_RE = re.compile(r"^\s*(```|~~~)")


def normalize(text: str) -> str:
    """Lowercase and collapse whitespace/underscore/hyphen runs to single spaces."""
    return re.sub(r"[\s_\-]+", " ", text.strip().lower())


def visible_lines(body: str) -> list[str]:
    """Strip HTML comments and fenced code blocks, return the remaining lines.

    Both are stripped so template *instructions* (kept in `<!-- -->`) and
    example snippets (kept in code fences) can never be mistaken for a
    real, contributor-written declaration. A disclosure that the author
    wrongly wrapped in a code fence is also excluded — which lines up with
    the plugin rule that voluntary disclosures must render as prose, not code.
    """
    body = HTML_COMMENT_RE.sub("", body)
    out: list[str] = []
    in_fence = False
    for line in body.splitlines():
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence:
            out.append(line)
    return out


def _section_has_content(lines: list[str], start: int) -> bool:
    """Any non-blank line between `start` and the next heading (exclusive)?"""
    for j in range(start + 1, len(lines)):
        if HEADING_RE.match(lines[j]):
            break
        if lines[j].strip():
            return True
    return False


def _disclosure_marker(line: str) -> str | None:
    """If `line` is a structural 'AI Disclosure' label, return the inline
    remainder after it (possibly empty); otherwise return None."""
    for pattern in DISCLOSURE_MARKERS:
        match = pattern.match(line)
        if match:
            return match.group("rest")
    return None


def has_ai_disclosure(lines: list[str]) -> bool:
    """True if an 'AI Disclosure' marker is followed by non-empty content."""
    for i, line in enumerate(lines):
        rest = _disclosure_marker(line)
        if rest is None:
            continue
        if rest.strip():
            return True
        if _section_has_content(lines, i):
            return True
    return False


def _matches_no_ai(text: str) -> bool:
    norm = normalize(text)
    return any(phrase in norm for phrase in NO_AI_PHRASES)


def has_no_ai_declaration(lines: list[str]) -> bool:
    """True if a checked no-AI box, or a freehand no-AI statement, is present.

    Checkbox lines are graded only via the checked-box rule: an *unchecked*
    `- [ ] ... without AI assistance` is the template's default, not a
    declaration, so it must not slip through the freehand scan.
    """
    for line in lines:
        checkbox = CHECKBOX_RE.match(line)
        if checkbox:
            if checkbox.group("mark") in ("x", "X") and _matches_no_ai(
                checkbox.group("label")
            ):
                return True
            continue
        if _matches_no_ai(line):
            return True
    return False


def evaluate(body: str) -> dict:
    lines = visible_lines(body)
    ai_disclosure = has_ai_disclosure(lines)
    no_ai = has_no_ai_declaration(lines)
    reasons: list[str] = []

    if ai_disclosure:
        reasons.append("Found an AI Disclosure section with content.")
    if no_ai:
        reasons.append("Found an explicit no-AI-assistance declaration.")

    verdict = "pass" if (ai_disclosure or no_ai) else "fail"
    if verdict == "fail":
        reasons.append(
            "No AI Disclosure section and no explicit no-AI declaration found "
            "in the PR description. If this contribution used AI assistance, add "
            "an AI Disclosure section; if it did not, check the no-AI box in the "
            "PR template."
        )

    return {
        "verdict": verdict,
        "ai_disclosure": ai_disclosure,
        "no_ai_declaration": no_ai,
        "reasons": reasons,
    }


def emit(data: dict | None, *, ok: bool, warnings=None, errors=None) -> None:
    payload = {
        "command": COMMAND,
        "ok": ok,
        "data": data,
        "warnings": list(warnings or []),
        "errors": list(errors or []),
    }
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def fail(message: str) -> int:
    sys.stderr.write(f"{COMMAND}: {message}\n")
    emit(None, ok=False, errors=[message])
    return 2


def read_body(body_file: str | None) -> str:
    if body_file is not None:
        with open(body_file, "r", encoding="utf-8") as handle:
            return handle.read()
    env_body = os.environ.get("PR_BODY")
    if env_body is not None:
        return env_body
    return sys.stdin.read()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Contribution-declaration gate check")
    parser.add_argument(
        "--body-file",
        default=None,
        help="Path to a file containing the PR body. Falls back to $PR_BODY, then stdin.",
    )
    args = parser.parse_args(argv)

    try:
        body = read_body(args.body_file)
    except OSError as exc:
        return fail(
            f"could not read PR body from {args.body_file!r}: {exc} — pass a "
            "readable --body-file, set $PR_BODY, or provide the body on stdin"
        )

    data = evaluate(body)
    emit(data, ok=True)
    return 0 if data["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
