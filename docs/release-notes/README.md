# Release notes

One file per version, named for the version: `0.7.0.md`. Whatever is in it becomes the Play
"What's new" and the App Store "What's New" for that release, byte for byte, in both stores.

Both store lanes in [`release.yml`](../../.github/workflows/release.yml) run
[`.github/scripts/release_notes.py`](../../.github/scripts/release_notes.py), which prefers the
file for the version being shipped. With no file it sanitises the GitHub release body instead,
which is a safety net and not the plan: it can strip markdown, commit links and `feat:` prefixes,
but it cannot tell which commits a player would care about, so it keeps a lot of plumbing and
writes in commit-subject voice. Write the file.

## Writing one

- **Plain text.** No markdown links, no headings (a `#` line is dropped), no URLs. A `- ` list
  is fine, and so is a short paragraph.
- **Under 500 characters** including line breaks. That is Play's per-locale limit, and it is the
  smaller of the two, so it sets the budget for both. Anything over is cut on a line boundary
  and the release logs a warning.
- **No em dashes.** House rule, and the script replaces them with a comma if one slips through.
- **The house voice** lives in [`../marketing-copy.md`](../marketing-copy.md): warm, plain,
  sincere, no marketing filler. Run the `unslop-text` skill over it before committing.
- **Say what a player would notice.** Not the fix, the thing they can now do. Leave out
  telemetry, CI, dashboards and refactors entirely.
- **A release with nothing user-facing says so.** That is an honest note, not a gap to fill with
  chores. `0.7.0.md` is the worked example.

## Checking it before you ship

Renders exactly what the stores will get:

```shell
python3 -I .github/scripts/release_notes.py --version 0.7.0 --body-file /dev/null
```

To see the fallback for a version with no file, pass a real release body:

```shell
gh release view v0.6.0 --json body -q .body \
  | python3 -I .github/scripts/release_notes.py --version 0.6.0
```
