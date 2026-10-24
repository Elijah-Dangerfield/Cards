#!/usr/bin/env python3
"""Merge the ads ledger, metric snapshots and latest report into dashboard.json.

Usage: python3 marketing/ads/build.py [--data marketing/ads/data]

Inputs (all inside the data dir):
  ledger.json            every ad we've run or planned
  snapshots/YYYY-MM-DD.json  lifetime totals per ad, as of that pull
  report.json            the latest judgment pass (verdicts, next action, prompts)

Output: dashboard.json in the same dir, the only file the dashboard reads.
Stdlib only, so it runs anywhere python3 does.
"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

METRIC_KEYS = (
    "spend", "impressions", "clicks", "videoViews",
    "views2s", "views3s", "views6s", "completions", "avgWatchSec", "installs",
)


def load_json(path, default):
    if not path.exists():
        return default
    with path.open() as f:
        return json.load(f)


def ratio(numerator, denominator):
    if not numerator or not denominator:
        return None
    return numerator / denominator


def load_snapshots(snapshot_dir):
    snapshots = []
    for path in sorted(snapshot_dir.glob("*.json")):
        data = load_json(path, {})
        data.setdefault("date", path.stem)
        snapshots.append(data)
    return snapshots


def latest_metrics(ad_id, snapshots):
    """Most recent non-empty totals for one ad, plus the date they were read."""
    for snap in reversed(snapshots):
        metrics = snap.get("ads", {}).get(ad_id)
        if metrics:
            return {k: metrics.get(k) for k in METRIC_KEYS}, snap["date"]
    return {k: None for k in METRIC_KEYS}, None


def derive(metrics):
    # Hook rate: share of impressions still watching after the opening.
    # TikTok reports 2-second views; Meta reports 3-second plays.
    hooked = metrics["views2s"] if metrics["views2s"] is not None else metrics["views3s"]
    return {
        "ctr": ratio(metrics["clicks"], metrics["impressions"]),
        "cpc": ratio(metrics["spend"], metrics["clicks"]),
        "cpi": ratio(metrics["spend"], metrics["installs"]),
        "installRate": ratio(metrics["installs"], metrics["clicks"]),
        "hookRate": ratio(hooked, metrics["impressions"]),
        "hookSeconds": 2 if metrics["views2s"] is not None else (3 if metrics["views3s"] is not None else None),
        # Hold rate: of those hooked, share still there at 6s.
        "holdRate": ratio(metrics["views6s"], hooked),
    }


BENCHMARK_FILE = Path(__file__).resolve().parent / "benchmarks.json"
# Metrics compared against benchmarks, in the order the dashboard shows them.
COMPARED = ("cpc", "ctr", "hookRate", "installRate", "cpi")


def benchmark_for(benchmarks, platform, metric):
    metrics = benchmarks.get("metrics", {})
    if metric == "cpi":
        # CPI benchmarks are per store; the ads so far target iPhone first.
        return metrics.get(platform, {}).get("cpiIos") or metrics.get("all", {}).get("cpiIos")
    if metric == "installRate":
        metric = "clickToInstall"
    return metrics.get(platform, {}).get(metric) or metrics.get("all", {}).get(metric)


def rate(value, bench):
    """'better', 'typical' or 'worse' than the normal range, or None."""
    if value is None or not bench:
        return None
    low, high = bench.get("low"), bench.get("high")
    if low is None or high is None:
        return None
    lower_is_better = bench.get("betterIs") == "lower"
    if value < low:
        return "better" if lower_is_better else "worse"
    if value > high:
        return "worse" if lower_is_better else "better"
    return "typical"


def comparisons(ad, benchmarks):
    rows = []
    for metric in COMPARED:
        value = ad["derived"].get(metric)
        bench = benchmark_for(benchmarks, ad["platform"], metric)
        if value is None or not bench:
            continue
        rows.append({"metric": metric, "value": value, "benchmark": bench, "rating": rate(value, bench)})
    return rows


def funnel(metrics):
    """Saw → watched the opening → clicked → installed, as counts."""
    hooked = metrics["views2s"] if metrics["views2s"] is not None else metrics["views3s"]
    hook_seconds = 2 if metrics["views2s"] is not None else 3
    steps = [
        {"label": "Saw the ad", "count": metrics["impressions"]},
        {"label": f"Watched {hook_seconds} seconds", "count": hooked},
        {"label": "Tapped the link", "count": metrics["clicks"]},
        {"label": "Installed", "count": metrics["installs"]},
    ]
    if not metrics["impressions"]:
        return None
    return steps


def spend_series(ads, snapshots):
    """Cumulative lifetime spend per platform, one point per snapshot date."""
    platforms = sorted({ad["platform"] for ad in ads})
    platform_of = {ad["id"]: ad["platform"] for ad in ads}
    dates, by_platform = [], {p: [] for p in platforms}
    last_known = {}
    for snap in snapshots:
        for ad_id, metrics in snap.get("ads", {}).items():
            if metrics.get("spend") is not None:
                last_known[ad_id] = metrics["spend"]
        dates.append(snap["date"])
        for platform in platforms:
            total = sum(
                spend for ad_id, spend in last_known.items()
                if platform_of.get(ad_id) == platform
            )
            by_platform[platform].append(round(total, 2))
    return {"dates": dates, "byPlatform": by_platform}


def build(data_dir):
    ledger = load_json(data_dir / "ledger.json", {"ads": []})
    report = load_json(data_dir / "report.json", {})
    snapshots = load_snapshots(data_dir / "snapshots")
    benchmarks = load_json(BENCHMARK_FILE, {})
    verdicts = report.get("verdicts", {})
    creative_notes = report.get("creativeNotes", {})

    ads = []
    for entry in ledger.get("ads", []):
        metrics, as_of = latest_metrics(entry["id"], snapshots)
        ad = {
            **entry,
            "metrics": metrics,
            "derived": derive(metrics),
            "metricsAsOf": as_of,
            "verdict": verdicts.get(entry["id"]),
            "creativeNotes": creative_notes.get(entry["id"]),
            "funnel": funnel(metrics),
        }
        ad["comparisons"] = comparisons(ad, benchmarks)
        ads.append(ad)

    spend = sum(ad["metrics"]["spend"] or 0 for ad in ads)
    installs = sum(ad["metrics"]["installs"] or 0 for ad in ads)
    clicks = sum(ad["metrics"]["clicks"] or 0 for ad in ads)
    budgets = [(ad.get("budget") or {}).get("amount") for ad in ads]

    return {
        "builtAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "lastPull": snapshots[-1]["date"] if snapshots else None,
        "totals": {
            "spend": round(spend, 2),
            # Only a real total when every ad has a known budget.
            "budget": round(sum(budgets), 2) if budgets and None not in budgets else None,
            "installs": installs,
            "clicks": clicks,
            "cpi": ratio(spend, installs),
            # Ads whose spend we couldn't read, so the totals are a floor.
            "spendUnknown": [ad["name"] for ad in ads if ad["metrics"]["spend"] is None],
            # Ads whose links carry no campaign token, so installs can't be counted.
            "untracked": [ad["name"] for ad in ads if not ad.get("campaignToken")],
        },
        "series": spend_series(ads, snapshots),
        "ads": ads,
        "placements": ledger.get("placements", []),
        "benchmarksChecked": benchmarks.get("checked"),
        "report": report,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--data", type=Path,
        default=Path(__file__).resolve().parent / "data",
        help="data directory (default: marketing/ads/data)",
    )
    args = parser.parse_args()
    if not (args.data / "ledger.json").exists():
        sys.exit(f"No ledger.json in {args.data}. Copy example-data/ledger.json to start.")
    out = args.data / "dashboard.json"
    out.write_text(json.dumps(build(args.data), indent=2) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
