#!/usr/bin/env bash
#
# Blocks a `gh pr create` / `gh pr edit` whose --title is not a Conventional
# Commit, before the call is made.
#
# Why this exists: `.github/workflows/commitlint.yml` already enforces the rule,
# but only after the PR is open and CI has run. That feedback arrives a minute
# later, as a red check on a PR that now needs editing. The same mistake was
# made repeatedly — `release:`, `AUTH-34:` — each time caught by CI rather than
# before the call. This is the same check, moved earlier.
#
# Reads the PreToolUse hook payload on stdin. Stays silent for every command
# that is not a gh PR title, and fails open if it cannot parse one: refusing a
# correct title because the parser choked would be worse than the mistake it
# prevents, and CI is still behind it.
#
# Keep the type list in step with .github/workflows/commitlint.yml.
set -uo pipefail

exec python3 -c '
import json, re, shlex, sys

# Must match the `types` list in .github/workflows/commitlint.yml.
TYPES = "feat|fix|perf|refactor|revert|docs|style|test|build|ci|chore"
# Optional scope, optional "!" for breaking. The subject may not start with an
# uppercase letter, mirroring commitlint subjectPattern ^(?![A-Z]).+$
TITLE = re.compile(r"^(" + TYPES + r")(\([^)]+\))?!?: (?![A-Z]).+")

def ok(): sys.exit(0)

def deny(title, why):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                "PR title is not a Conventional Commit, so the `Validate PR title` "
                "check will fail.\n\n"
                "  got:  " + title + "\n"
                "  " + why + "\n\n"
                "  type must be one of: " + TYPES.replace("|", ", ") + "\n"
                "  shape: type(optional-scope): subject starting lowercase\n"
                "  e.g.   fix(identity): keep the stranded account recoverable\n\n"
                "A ticket id is not a type. Put it in the body or the scope."
            ),
        }
    }))
    sys.exit(0)

try:
    cmd = json.load(sys.stdin).get("tool_input", {}).get("command", "")
except Exception:
    ok()

if "gh" not in cmd or " pr " not in cmd:
    ok()

try:
    tokens = shlex.split(cmd)
except ValueError:
    ok()  # unbalanced quotes / heredoc we cannot read: let CI catch it

# Find a `gh ... pr (create|edit)` anywhere in the command (it is often behind
# a `cd x &&`), then the --title that call carries.
found = False
for i, t in enumerate(tokens):
    if t == "gh":
        rest = tokens[i + 1 : i + 4]
        if "pr" in rest and ("create" in rest or "edit" in rest):
            found = True
            break
if not found:
    ok()

title = None
for i, t in enumerate(tokens):
    if t in ("--title", "-t") and i + 1 < len(tokens):
        title = tokens[i + 1]
        break
    if t.startswith("--title="):
        title = t[len("--title="):]
        break

# `gh pr create` with no --title opens an editor or derives from the branch;
# nothing to check here.
if title is None:
    ok()

if TITLE.match(title):
    ok()

head = title.split(":", 1)[0] if ":" in title else title
if ":" not in title:
    why = "no `type:` prefix at all"
elif not re.match(r"^(" + TYPES + r")(\([^)]+\))?!?$", head):
    why = "`" + head + "` is not a valid type"
else:
    why = "subject must not start with an uppercase letter"

deny(title, why)
'
