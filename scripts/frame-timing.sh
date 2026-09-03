#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Measures how long frames take, in whatever state you put the app in.
#
# Built for ENG-49: four production ANRs show the RenderThread wedged in Skia's
# glyph cache under a text draw, and the open question is *which* text. The
# leading suspect is the card flip, because text redrawn at a new scale or
# rotation every frame cannot reuse a cached glyph blob.
#
# So this is a discriminating test, not a confirming one. Measure the table
# idle, then during a deal, then at showdown. If the deal spikes and the others
# don't, the cards are the problem. If all three are equally bad, they aren't,
# and the plan's hypothesis is wrong.
#
# Usage:  scripts/frame-timing.sh
#         (prompts you between states; no arguments)
#
# Reads `dumpsys gfxinfo <pkg> framestats`, which reports per-frame timings for
# the last ~120 frames. Absolute numbers are not comparable across devices or
# between debug and release, so only ever compare states captured in one run on
# one device.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

PKG=$(adb shell pm list packages 2>/dev/null | grep dangerfield.cards | sed 's/package://' | tr -d '\r' | head -1)
if [ -z "$PKG" ]; then
  echo "No Downcard package found on the connected device. Is it installed, and is adb connected?" >&2
  exit 1
fi
echo "Measuring $PKG"
echo

# Some OEM builds need this before framestats emits a PROFILEDATA block. It is a
# no-op where the block is already there, so it's cheaper to set it than to
# explain the empty output.
adb shell setprop debug.hwui.profile true >/dev/null 2>&1

capture() {
  local label="$1"
  printf '\n>>> Get the app into this state: %s\n    Press Return to start a 6-second capture: ' "$label"
  read -r _
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
  echo "    capturing..."
  sleep 6
  adb shell dumpsys gfxinfo "$PKG" framestats 2>/dev/null > "/tmp/frames-$label.txt"
  python3 - "$label" <<'PY'
import re, statistics, sys
label = sys.argv[1]
rows, inblock = [], False
for line in open(f"/tmp/frames-{label}.txt"):
    s = line.strip()
    if "PROFILEDATA" in s:
        inblock = not inblock
        continue
    if not inblock or not re.match(r"^\d", s):
        continue
    p = s.split(",")
    # Columns per the header this block prints: IntendedVsync is index 2 and
    # FrameCompleted is index 17. Total frame time is the difference.
    if len(p) <= 17:
        continue
    try:
        intended, done = int(p[2]), int(p[17])
    except ValueError:
        continue
    if done > intended:
        rows.append((done - intended) / 1e6)
if not rows:
    print(f"    {label}: no frames captured — was the app in the foreground and drawing?")
else:
    rows.sort()
    p50 = statistics.median(rows)
    p90 = rows[int(len(rows) * 0.9) - 1]
    print(f"    {label}: {len(rows):3d} frames   p50 {p50:6.1f}ms   p90 {p90:6.1f}ms   worst {max(rows):7.1f}ms")
PY
}

cat <<'INTRO'
Three states, in this order. Get a bots game going first.

  1. idle      — your turn, nothing animating. The baseline.
  2. deal      — start the capture, then trigger the next hand so cards fly and flip.
  3. showdown  — capture while cards reveal at the end of a hand.

A frame budget is ~16ms at 60Hz, ~11ms at 90Hz. What matters is not the absolute
number but whether `deal` is dramatically worse than `idle`.
INTRO

capture idle
capture deal
capture showdown

cat <<'OUTRO'

Raw captures are in /tmp/frames-*.txt if you want to dig in.

Reading it:
  deal >> idle          the card animation is the cost. Hypothesis holds; go
                        make PlayingCard's rank and suit a vector and re-run.
  all three equally bad the text load is always-on and the cards are innocent.
                        Look at what else draws text every frame — chip stacks,
                        pot, odds, timers.
  all three fine        this device is too fast to show it. Try a slower phone
                        before concluding anything.
OUTRO
