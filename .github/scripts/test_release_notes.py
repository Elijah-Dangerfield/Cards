#!/usr/bin/env python3
"""
Tests for release_notes.py. Run from anywhere:

    python3 -I .github/scripts/test_release_notes.py

Why it is worth testing: this transform only runs during a release, and its
output goes straight onto the store listing. A regression here is discovered by
a player reading markdown in "What's New", days after it shipped.

The 0.6.0 body below is the real one from the GitHub release, verbatim. It is
the case that caused ENG-86.
"""

from __future__ import annotations

import importlib.util
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "release_notes", Path(__file__).resolve().parent / "release_notes.py"
)
rn = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rn)

BODY_060 = """## [0.6.0](https://github.com/Elijah-Dangerfield/Cards/compare/v0.5.0...v0.6.0) (2026-10-21)


### Features

* **auth:** offer the stranded account back after a session loss ([7998719](https://github.com/Elijah-Dangerfield/Cards/commit/799871933a069aa59eb4ae3a8068a19c33d5ad7f))
* **home:** put the score against your regular opponent on Home ([12d020b](https://github.com/Elijah-Dangerfield/Cards/commit/12d020bc6d33fd87827675e14a750a5765429a22))
* **identity:** record the stranded account so the healer keeps refusing (AUTH-34) ([a75f90b](https://github.com/Elijah-Dangerfield/Cards/commit/a75f90b1a39fefa6e48ae3fea11edc5cd5fad4b5))
* **matchmaking:** stop asking for patience when nobody is online ([1eb6a89](https://github.com/Elijah-Dangerfield/Cards/commit/1eb6a8983954dbdcdabfb5b37e1999f768a2086c))


### Bug Fixes

* **build:** desugar java.time so the app survives API 24 and 25 ([9fc63eb](https://github.com/Elijah-Dangerfield/Cards/commit/9fc63eb8b7c8f5a52f0567a5ae874cffbf112bd2))
* **home:** never reveal the wallet balance as the starter grant (AUTH-33) ([d4eafca](https://github.com/Elijah-Dangerfield/Cards/commit/d4eafca0e52b5a4a6444690efa38489f247d74b5))
* **telemetry:** get money-path events out of RAM before the session ends ([a25e350](https://github.com/Elijah-Dangerfield/Cards/commit/a25e3507dfb3a4ded732dbf05755929f2fd0d5c4))
* the API-level bug that silenced telemetry, and the lint that will not catch the next one ([8257332](https://github.com/Elijah-Dangerfield/Cards/commit/82573322fed2ce54f70b008568a34bbad1a9891b))
* **website:** point store buttons at the live App Store and Play listings ([f3d635f](https://github.com/Elijah-Dangerfield/Cards/commit/f3d635f570e1de7413e3279d9952ab356c739562))
"""


def render(version: str, body: str, notes_dir: str, max_chars: int = 500) -> str:
    """Runs the script end to end the way the workflow does."""
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as handle:
        handle.write(body)
        body_path = handle.name
    argv = sys.argv
    sys.argv = [
        "release_notes.py",
        "--version",
        version,
        "--body-file",
        body_path,
        "--notes-dir",
        notes_dir,
        "--max-chars",
        str(max_chars),
    ]
    out = io.StringIO()
    try:
        with redirect_stdout(out), redirect_stderr(io.StringIO()):
            assert rn.main() == 0
    finally:
        sys.argv = argv
        os.unlink(body_path)
    return out.getvalue().rstrip("\n")


class SanitiseRealBody(unittest.TestCase):
    def setUp(self) -> None:
        self.empty = tempfile.mkdtemp()
        self.notes = render("0.6.0", BODY_060, self.empty)

    def test_no_markdown_or_links_survive(self) -> None:
        for tell in ("##", "**", "](", "http", "[0.6.0]"):
            self.assertNotIn(tell, self.notes, f"{tell!r} reached the listing")

    def test_no_commit_shas_or_ticket_ids_survive(self) -> None:
        self.assertNotIn("7998719", self.notes)
        self.assertNotIn("AUTH-34", self.notes)
        self.assertNotIn("AUTH-33", self.notes)

    def test_no_conventional_commit_prefixes_survive(self) -> None:
        self.assertNotIn("feat", self.notes)
        self.assertNotIn("fix:", self.notes)
        for scope in ("auth:", "home:", "identity:", "matchmaking:"):
            self.assertNotIn(scope, self.notes.lower())

    def test_internal_only_changes_are_left_out(self) -> None:
        self.assertNotIn("desugar", self.notes)
        self.assertNotIn("RAM", self.notes)
        self.assertNotIn("store buttons", self.notes)
        self.assertNotIn("lint", self.notes)

    def test_player_facing_changes_are_kept_as_sentences(self) -> None:
        self.assertIn("- Stop asking for patience when nobody is online.", self.notes)
        self.assertIn("- Never reveal the wallet balance as the starter grant.", self.notes)
        for line in self.notes.splitlines():
            self.assertTrue(line.startswith("- "), line)
            self.assertTrue(line.endswith((".", "!", "?")), line)

    def test_no_em_dashes(self) -> None:
        self.assertNotIn("—", self.notes)
        self.assertNotIn("–", self.notes)

    def test_fits_the_play_budget(self) -> None:
        self.assertLessEqual(len(self.notes), 500)


class SanitiseEdgeCases(unittest.TestCase):
    def sanitise(self, body: str) -> list[str]:
        return rn.sanitise_body(body)

    def test_scope_outside_the_bold_is_stripped_too(self) -> None:
        self.assertEqual(
            ["Deal a fresh hand."], self.sanitise("* **play**: deal a fresh hand")
        )

    def test_bare_conventional_prefix_is_stripped(self) -> None:
        self.assertEqual(
            ["Deal a fresh hand."], self.sanitise("- feat(play): deal a fresh hand")
        )

    def test_breaking_change_marker_is_stripped(self) -> None:
        self.assertEqual(["Reshuffle the deck."], self.sanitise("* feat(play)!: reshuffle the deck"))

    def test_bare_sha_and_url_are_dropped(self) -> None:
        self.assertEqual(
            ["Fix the shop."],
            self.sanitise("* **shop:** fix the shop a25e350 https://example.com/x"),
        )

    def test_a_hex_looking_word_is_not_mistaken_for_a_sha(self) -> None:
        self.assertEqual(
            ["Keep the facade intact."], self.sanitise("* **ui:** keep the facade intact")
        )

    def test_em_dash_becomes_a_comma(self) -> None:
        self.assertEqual(
            ["Sit down faster, no waiting."],
            self.sanitise("* **lobby:** sit down faster — no waiting"),
        )

    def test_trailing_refs_footer_is_dropped(self) -> None:
        self.assertEqual(
            ["Deal faster."], self.sanitise("* **play:** deal faster (#412) (MP-30) Refs")
        )

    def test_headings_and_blank_lines_are_ignored(self) -> None:
        self.assertEqual([], self.sanitise("## [0.6.0](http://x) (2026-10-21)\n\n### Features\n"))

    def test_plain_prose_body_is_kept(self) -> None:
        self.assertEqual(
            ["We rebuilt the table."], self.sanitise("we rebuilt the table")
        )


class WordBoundaryTruncation(unittest.TestCase):
    def test_one_long_line_is_cut_between_words(self) -> None:
        notes = render(
            "9.9.9",
            "* **play:** " + " ".join(["unbelievably"] * 40),
            tempfile.mkdtemp(),
            max_chars=60,
        )
        self.assertLessEqual(len(notes), 60)
        self.assertTrue(notes.endswith("."))
        self.assertNotIn("unbeliev.", notes)
        for word in notes.removeprefix("- ").rstrip(".").split():
            self.assertEqual("unbelievably", word.lower())

    def test_dropped_items_are_acknowledged(self) -> None:
        notes = render("9.9.9", BODY_060, tempfile.mkdtemp(), max_chars=200)
        self.assertLessEqual(len(notes), 200)
        self.assertTrue(notes.endswith(rn.MORE_TO_COME))


class NothingUserFacing(unittest.TestCase):
    def test_an_all_internal_release_says_so_honestly(self) -> None:
        body = (
            "### Bug Fixes\n\n"
            "* **ci:** stop an unapproved prod deploy blocking every later one\n"
            "* **telemetry:** close the purchase funnel\n"
        )
        self.assertEqual(rn.NOTHING_USER_FACING, render("9.9.9", body, tempfile.mkdtemp()))

    def test_no_body_at_all_does_not_claim_there_were_no_features(self) -> None:
        self.assertEqual(rn.UNKNOWN_CHANGES, render("9.9.9", "", tempfile.mkdtemp()))


class AuthoredNotes(unittest.TestCase):
    def write(self, version: str, text: str) -> str:
        directory = tempfile.mkdtemp()
        Path(directory, f"{version}.md").write_text(text, encoding="utf-8")
        return directory

    def test_a_hand_written_note_wins_over_the_release_body(self) -> None:
        directory = self.write("0.6.0", "# 0.6.0\n\nYour rivalries now show on Home.\n")
        self.assertEqual(
            "Your rivalries now show on Home.", render("0.6.0", BODY_060, directory)
        )

    def test_the_authors_line_breaks_survive(self) -> None:
        directory = self.write("1.0.0", "Two things:\n\n- One.\n- Two.\n")
        self.assertEqual("Two things:\n\n- One.\n- Two.", render("1.0.0", "", directory))

    def test_an_over_long_note_is_cut_on_a_line_boundary(self) -> None:
        directory = self.write("1.0.0", "Short line.\n" + "x" * 80 + "\n")
        self.assertEqual("Short line.", render("1.0.0", "", directory, max_chars=40))

    def test_an_empty_note_falls_back_to_the_body(self) -> None:
        directory = self.write("1.0.0", "\n\n")
        self.assertIn("Stop asking for patience", render("1.0.0", BODY_060, directory))

    def test_an_em_dash_in_a_hand_written_note_is_replaced(self) -> None:
        directory = self.write("1.0.0", "Faster tables — fewer waits.\n")
        self.assertEqual("Faster tables, fewer waits.", render("1.0.0", "", directory))


class BothLanesAgree(unittest.TestCase):
    def test_same_arguments_give_the_same_copy(self) -> None:
        directory = tempfile.mkdtemp()
        play = render("0.6.0", BODY_060, directory)
        app_store = render("0.6.0", BODY_060, directory)
        self.assertEqual(play, app_store)


if __name__ == "__main__":
    unittest.main(verbosity=2)
