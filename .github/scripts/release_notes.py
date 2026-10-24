#!/usr/bin/env python3
"""
Renders the store-facing "What's New" text for one release.

Why this exists: both store lanes in release.yml used to pipe
`gh release view --json body` straight into the listing, cut at a byte count.
So the App Store "What's New" for 0.6.0 opened with

    ## [0.6.0](https://github.com/.../compare/v0.5.0...v0.6.0) (2026-10-21)
    * **auth:** offer the stranded account back after a session loss ([7998719](...))

which is release-please's internal changelog, markdown and all, shown to
players. Release notes are the first thing a new player reads on the listing.

Two sources, in order:

  1. docs/release-notes/<version>.md — hand-written, wins whenever it exists.
     This is the real mechanism. Someone who knows what shipped writes a few
     sentences in the app's voice.
  2. The GitHub release body, sanitised — the safety net for when nobody wrote
     one. It can strip syntax and internal references; it cannot know which
     commits a player would care about, so it guesses from the conventional-
     commit scope and drops what reads as plumbing.

Both lanes call this with the same arguments, so Play and the App Store show
identical copy. The 500-character budget is Play's per-locale limit, which is
the smaller of the two, so one budget keeps them in step.

Writes the notes to stdout, warnings to stderr. Exits non-zero only on bad
usage: a release must never fail because the notes came out thin.

    gh release view v0.6.0 --json body -q .body \\
      | python3 .github/scripts/release_notes.py --version 0.6.0
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PLAY_MAX_CHARS = 500

# Conventional-commit scopes that never describe something a player can see.
# Deliberately short: a scope missing from this list shows up as a slightly
# dull line, while a scope wrongly on it hides a real feature.
INTERNAL_SCOPES = frozenset(
    {
        "admin",
        "agent",
        "build",
        "cd",
        "ci",
        "deps",
        "deps-dev",
        "detekt",
        "docs",
        "gradle",
        "infra",
        "lint",
        "observability",
        "qa",
        "release",
        "telemetry",
        "test",
        "tests",
        "tooling",
        "web",
        "website",
    }
)

# Words that only exist on our side of the app. A commit subject mentioning one
# is about the pipeline, the dashboards or the build, whatever its scope says.
INTERNAL_TERMS = re.compile(
    r"\b("
    r"changelog|ci|dashboards?|detekt|flaky|grafana|gradle|lint|loki|sentry"
    r"|telemetry|tempo|workflows?"
    r")\b",
    re.IGNORECASE,
)

HEADING = re.compile(r"^#{1,6}\s")
BULLET = re.compile(r"^\s*[*+-]\s+")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
# release-please's commit link: `([7998719](https://github.com/.../commit/...))`
COMMIT_REF = re.compile(r"\s*\(\[[^\]]*]\([^)]*\)\)")
PR_REF = re.compile(r"\s*\(#\d+\)")
MD_LINK = re.compile(r"\[([^\]]*)]\([^)]*\)")
# release-please writes the scope as `**auth:**`, with the colon inside the
# bold. Accept `**auth**:` too rather than depending on which side it lands on.
BOLD_SCOPE = re.compile(r"^\*\*([a-z0-9][a-z0-9/_-]*):?\*\*:?\s*")
TYPE_PREFIX = re.compile(
    r"^(feat|fix|perf|refactor|revert|style|chore|docs|test|build|ci)"
    r"(?:\(([a-z0-9][a-z0-9/_-]*)\))?!?:\s*",
    re.IGNORECASE,
)
MD_EMPHASIS = re.compile(r"[*_`]+")
BARE_URL = re.compile(r"\s*<?https?://\S+>?")
# Internal ticket id: AUTH-34, ENG-71, MP-30. Needs the digits, so it can't
# swallow a hyphenated word.
TICKET = re.compile(r"\s*\(?\b[A-Z][A-Z0-9]{1,5}-\d+\b\)?")
TRAILING_REF = re.compile(r"\s*(?:refs?|see|closes?|fixes)\s*$", re.IGNORECASE)
# A bare commit sha. Hex-only and seven-plus characters is not enough on its
# own ("deadbeef" is a word-shaped accident), so require a digit too.
BARE_SHA = re.compile(r"\b(?=[0-9a-f]*\d)[0-9a-f]{7,40}\b")
EM_DASHES = re.compile(r"\s*[—–]\s*")

NOTHING_USER_FACING = (
    "No new features this time. This one is fixes and clean-up "
    "behind the scenes, so the app should run a little better."
)
# Used only when there is no hand-written note *and* no release body to read,
# which means both sources failed and we genuinely don't know what shipped.
# Saying "no new features" there would be a guess, and it could be a wrong one.
UNKNOWN_CHANGES = "Small fixes and improvements."
MORE_TO_COME = "- Plus a few smaller fixes."


def strip_markdown(text: str) -> str:
    text = COMMIT_REF.sub("", text)
    text = PR_REF.sub("", text)
    text = MD_LINK.sub(r"\1", text)
    text = BARE_URL.sub("", text)
    text = MD_EMPHASIS.sub("", text)
    text = BARE_SHA.sub("", text)
    text = TICKET.sub("", text)
    text = EM_DASHES.sub(", ", text)
    text = re.sub(r"\(\s*\)", "", text)
    text = re.sub(r"\s+([,.;:])", r"\1", text)
    return re.sub(r"\s{2,}", " ", text).strip(" ,;:")


def as_sentence(text: str) -> str:
    text = TRAILING_REF.sub("", text).strip()
    if not text:
        return ""
    text = text[0].upper() + text[1:]
    return text if text[-1] in ".!?" else text + "."


def is_internal(scope: str | None, text: str) -> bool:
    if scope and scope.lower() in INTERNAL_SCOPES:
        return True
    return INTERNAL_TERMS.search(text) is not None


def sanitise_body(body: str) -> list[str]:
    """Turns a release-please release body into plain player-facing sentences."""
    items: list[str] = []
    for raw in HTML_COMMENT.sub("", body).splitlines():
        line = raw.strip()
        if not line or HEADING.match(line):
            continue
        line = BULLET.sub("", line)

        scope = None
        bold = BOLD_SCOPE.match(line)
        if bold:
            scope = bold.group(1)
            line = line[bold.end() :]
        else:
            typed = TYPE_PREFIX.match(line)
            if typed:
                scope = typed.group(2)
                line = line[typed.end() :]

        sentence = as_sentence(strip_markdown(line))
        if sentence and not is_internal(scope, sentence):
            items.append(sentence)
    return items


def read_authored(path: Path) -> list[str]:
    """Reads a hand-written note, keeping the author's wording and line breaks."""
    lines: list[str] = []
    for raw in HTML_COMMENT.sub("", path.read_text(encoding="utf-8")).splitlines():
        line = raw.rstrip()
        if HEADING.match(line.strip()):
            continue
        lines.append(EM_DASHES.sub(", ", line))
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return lines


def fit(lines: list[str], max_chars: int) -> tuple[str, int]:
    """Trims to the budget on a line boundary, or on a word if one line is over.

    Returns the text and how many lines it had to leave out.
    """
    kept: list[str] = []
    for index, line in enumerate(lines):
        candidate = "\n".join(kept + [line])
        if len(candidate) <= max_chars:
            kept.append(line)
            continue
        if kept:
            return "\n".join(kept).strip(), len(lines) - index
        words = line.split()
        clipped = ""
        for word in words:
            nxt = f"{clipped} {word}".strip()
            if len(nxt) > max_chars - 1:
                break
            clipped = nxt
        # The clipped line itself counts as shown, so only the lines after it
        # are dropped. A single over-long line has nothing left to announce.
        return (clipped.rstrip(" ,;:") + "." if clipped else ""), len(lines) - 1
    return "\n".join(kept).strip(), 0


def warn(message: str) -> None:
    print(f"::warning::{message}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="e.g. 0.6.1")
    parser.add_argument(
        "--body-file",
        help="Release body to fall back to. Reads stdin when omitted.",
    )
    parser.add_argument(
        "--notes-dir",
        default="docs/release-notes",
        help="Where hand-written notes live (default: %(default)s).",
    )
    parser.add_argument(
        "--max-chars",
        type=int,
        default=PLAY_MAX_CHARS,
        help="Character budget, Play's per-locale limit (default: %(default)s).",
    )
    args = parser.parse_args()

    authored = Path(args.notes_dir) / f"{args.version}.md"
    if authored.is_file():
        lines = read_authored(authored)
        if lines:
            notes, dropped = fit(lines, args.max_chars)
            if dropped:
                warn(
                    f"{authored} is longer than {args.max_chars} characters, so the "
                    f"last {dropped} line(s) were cut. Shorten it so the stores "
                    "show all of it."
                )
            print(notes)
            return 0
        warn(f"{authored} is empty. Falling back to the release body.")
    else:
        warn(
            f"No {authored}, so the stores get the sanitised release body. "
            "A hand-written note reads better; write one before the next release."
        )

    body = (
        Path(args.body_file).read_text(encoding="utf-8")
        if args.body_file
        else sys.stdin.read()
    )
    if not body.strip():
        warn(
            "The release body is empty too, so the stores get a generic line. "
            "Check whether `gh release view` failed."
        )
        print(UNKNOWN_CHANGES)
        return 0

    items = sanitise_body(body)
    if not items:
        print(NOTHING_USER_FACING)
        return 0

    lines = [f"- {item}" for item in items]
    notes, dropped = fit(lines, args.max_chars)
    tail_budget = args.max_chars - len(MORE_TO_COME) - 1
    if dropped and tail_budget > 0:
        # Leaving the rest off silently reads as though that was everything.
        trimmed, _ = fit(lines, tail_budget)
        notes = f"{trimmed}\n{MORE_TO_COME}"
    print(notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
