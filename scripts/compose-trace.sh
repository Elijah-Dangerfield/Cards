#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Records a Perfetto trace of the debug app, with Compose composition tracing on,
# without needing Android Studio.
#
# Composition tracing is what turns "some frame was slow" into "this composable
# recomposed 471 times". It needs two things: the runtime-tracing dependency
# (in apps/compose/build.gradle.kts, debug only) and a broadcast to load the
# native library at runtime. This script does the broadcast for you.
#
# Usage:  scripts/compose-trace.sh [seconds]      # default 15
#
# Play through whatever you want measured during the countdown. The trace lands
# on your Desktop; hand the path to Claude, or open it at ui.perfetto.dev.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

PKG="com.dangerfield.cards.debug"
SECONDS_TO_RECORD="${1:-15}"
OUT="$HOME/Desktop/compose-trace-$(date +%Y%m%d-%H%M%S).perfetto-trace"

if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Plug in the phone, allow USB debugging, and try again." >&2
  exit 1
fi
if ! adb shell pm list packages 2>/dev/null | grep -q "$PKG"; then
  echo "$PKG isn't installed. Run: ./gradlew :apps:compose:installDebug" >&2
  exit 1
fi

echo "Enabling composition tracing…"
RESULT=$(adb shell am broadcast \
  -a androidx.tracing.perfetto.action.ENABLE_TRACING \
  "$PKG/androidx.tracing.perfetto.TracingReceiver" 2>&1)
# exitCode 1 = enabled, 2 = already enabled. 11 = the native library is missing,
# which means the debug build predates the tracing-perfetto-binary dependency.
if echo "$RESULT" | grep -q '"exitCode":11'; then
  echo "The installed build has no tracing binary. Reinstall: ./gradlew :apps:compose:installDebug" >&2
  exit 1
fi
echo "  ok"

cat > /tmp/compose-trace.pbtxt <<CFG
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_process_exit"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_apps: "$PKG"
    }
  }
}
data_sources { config { name: "track_event" } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: $((SECONDS_TO_RECORD * 1000))
CFG

adb push /tmp/compose-trace.pbtxt /data/local/tmp/compose-trace.pbtxt >/dev/null 2>&1

echo
echo ">>> RECORDING FOR ${SECONDS_TO_RECORD}s — do the thing you want measured NOW <<<"
adb shell "cat /data/local/tmp/compose-trace.pbtxt | perfetto --txt -c - -o /data/misc/perfetto-traces/compose.pftrace" >/dev/null 2>&1
echo "    done"

adb pull /data/misc/perfetto-traces/compose.pftrace "$OUT" >/dev/null 2>&1
if [ -s "$OUT" ]; then
  echo
  echo "Wrote $OUT  ($(du -h "$OUT" | cut -f1))"
  echo "Hand that path over, or drop it on https://ui.perfetto.dev"
else
  echo "Nothing came back. Is the device still connected?" >&2
  exit 1
fi
