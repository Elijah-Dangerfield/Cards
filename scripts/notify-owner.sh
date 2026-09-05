#!/bin/bash
# Text the owner, via the Mac's own Messages app.
#
# Used by the observability routine to escalate the small set of findings that
# cannot wait for someone to read a todo list — a store deadline, an app
# rejection, money stuck in the purchase pipeline. Everything else the routine
# finds becomes a todo and is read whenever.
#
# WHY MESSAGES AND NOT TWILIO/GRAFANA ONCALL
#
# Grafana's alerts already page through OnCall, and they should keep owning
# real-time incidents: they run in the cloud and fire whether or not this laptop
# is awake. This script covers the other half — findings that come from reading
# a dashboard trend, an inbox, or a Sentry issue, which is judgement work that
# only happens when the routine runs.
#
# The routine runs locally. So does Messages. If the Mac is asleep the routine
# does not run at all, which means it has nothing to send — the two constraints
# are the same one, and nothing is silently lost. A cloud SMS provider would add
# an account, a credential and a monthly bill to deliver messages that only get
# produced while this machine is awake anyway.
#
# THE NUMBER IS NOT IN THIS FILE ON PURPOSE
#
# This repository is public. The destination lives in the login keychain under
# `cards-owner-phone`, the same pattern the Sentry token uses. Set it with:
#
#   security add-generic-password -U -s cards-owner-phone -a "$USER" -w "+1XXXXXXXXXX"
#
# Usage:  scripts/notify-owner.sh "message text"
# Exit:   0 sent · 1 bad usage · 2 no number configured · 3 Messages refused

set -euo pipefail

if [ $# -lt 1 ] || [ -z "${1:-}" ]; then
  echo "usage: $(basename "$0") \"message text\"" >&2
  exit 1
fi

MESSAGE="$1"

# Env var wins so a test run can retarget without touching the keychain.
PHONE="${CARDS_OWNER_PHONE:-$(security find-generic-password -s cards-owner-phone -w 2>/dev/null || true)}"

if [ -z "$PHONE" ]; then
  echo "::error::No owner phone configured (keychain item 'cards-owner-phone')." >&2
  exit 2
fi

# Messages' scripting dictionary changed across recent macOS releases: the
# `service`/`buddy` forms that most snippets online use now fail with -1728 on
# some versions. Try the account/participant form first and keep the older one
# as a fallback, so a macOS update degrades to "try the other syntax" rather
# than to silence. Both are reported, because a notifier that fails quietly is
# worse than no notifier — you would trust it and hear nothing.
RESULT=$(osascript <<APPLESCRIPT 2>&1 || true
tell application "Messages"
	set theMsg to "$(printf '%s' "$MESSAGE" | sed 's/\\/\\\\/g; s/"/\\"/g')"
	try
		set acct to 1st account whose service type = iMessage
		send theMsg to participant "$PHONE" of acct
		return "ok:imessage"
	on error e1
		try
			send theMsg to buddy "$PHONE"
			return "ok:buddy"
		on error e2
			return "err: " & e1 & " || " & e2
		end try
	end try
end tell
APPLESCRIPT
)

case "$RESULT" in
  ok:*)
    echo "notified owner ($RESULT)"
    ;;
  *)
    # Most likely causes, in order: Messages is not signed in, or this process
    # has no Automation permission for Messages (System Settings → Privacy &
    # Security → Automation). Neither is fixable from here.
    echo "::error::Messages refused to send: $RESULT" >&2
    exit 3
    ;;
esac
