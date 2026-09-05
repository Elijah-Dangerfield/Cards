#!/bin/bash
# The scheduled observability sweep. Driven by launchd; see scripts/README.md.
#
# Runs `observability-triage` headless: Sentry, eight Grafana boards, and the
# store inbox, in, out, todos and case files committed to develop. It texts only
# for the narrow set the skill calls out (lapsing deadline, blocked shipping,
# stuck money) and is otherwise silent by design.
#
# WHY LOCAL AND NOT A CLOUD ROUTINE
#
# The sweep needs three things a cloud agent does not have: the Grafana MCP
# (configured in this Claude Code, not a claude.ai connector), the Gmail MCP for
# the store inbox, and Messages.app to send the text. A cloud routine also needs
# the Claude GitHub App on the repo, which is not installed. Running here is not
# a compromise — it is the only place all three exist at once.
#
# The obvious cost is that a sleeping Mac runs nothing. That is survivable and
# deliberate: real-time incidents are not this script's job. Grafana's A1–A8
# alerts run in the cloud and page through OnCall whether or not this laptop is
# awake. This is the slower judgement layer that reads dashboards and mail, and
# a few hours late is fine for a policy deadline three weeks out.
#
# launchd catches up a missed run when the Mac wakes (RunAtLoad plus a missed
# StartCalendarInterval), so a closed lid delays the sweep, it does not skip it.

set -uo pipefail

REPO="/Users/elijahdangerfield/Workspace/Cards"
LOG_DIR="$HOME/Library/Logs/cards-routine"
LOG="$LOG_DIR/observability-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$LOG_DIR"
cd "$REPO" || { echo "repo missing at $REPO" >&2; exit 1; }

# Keep the log directory from growing without bound. Thirty days is well past
# the point where a run is still worth reading, and these are the only record of
# what an unattended sweep did.
find "$LOG_DIR" -name 'observability-*.log' -mtime +30 -delete 2>/dev/null

exec >>"$LOG" 2>&1
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) starting observability sweep ==="

# launchd hands over a minimal PATH that has neither the Claude CLI nor
# Homebrew, so a run started by the scheduler would fail in a way a run started
# by hand never does.
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

if ! command -v claude >/dev/null 2>&1; then
  echo "claude CLI not on PATH; aborting"
  exit 1
fi

# Never sweep on top of half-finished work. The skill commits its own findings,
# and committing someone's uncommitted edits along with them would be worse than
# skipping a night.
if [ -n "$(git status --porcelain)" ]; then
  echo "working tree dirty; skipping this run to avoid committing someone's WIP"
  git status --short
  exit 0
fi

git checkout develop --quiet 2>/dev/null
git pull --ff-only --quiet 2>/dev/null || echo "warn: could not fast-forward develop; sweeping against local state"

claude --permission-mode acceptEdits -p "$(cat <<'PROMPT'
You are running the Cards observability sweep, unattended, in /Users/elijahdangerfield/Workspace/Cards on branch develop.

Invoke the `observability-triage` skill with the Skill tool and follow it exactly, end to end: all three intake channels (Sentry, the eight Grafana boards, the store inbox), then dispositions, the ledger, and the run summary.

Rules for this unattended run:
- File todos and case files, commit them to `develop` with a conventional-commit message, and push. Do not open a PR and do not merge anything.
- Never reset, rebase or force-push. Never touch application code — you are triaging, not fixing.
- Text via scripts/notify-owner.sh ONLY for the step 7 set (a lapsing deadline, blocked shipping, stuck money). One message for the whole run, or none. Silence is the normal outcome.
- If a data source is unreachable, say so in the summary and carry on with the rest rather than aborting the sweep.

End with the run summary from step 8.
PROMPT
)"

STATUS=$?
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) finished, exit $STATUS ==="

# A sweep that cannot run is itself a finding: silence would otherwise be
# indistinguishable from "nothing was wrong", which is the exact failure mode
# this whole routine exists to avoid.
if [ $STATUS -ne 0 ]; then
  "$REPO/scripts/notify-owner.sh" "Downcard: the observability sweep failed (exit $STATUS). Nothing was triaged tonight. Log: $LOG" || true
fi

exit $STATUS
