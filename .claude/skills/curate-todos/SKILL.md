---
name: curate-todos
description: Reconcile docs/todo.md against the repo (drop shipped/stale items, trim bloat) and top it up only if thin. The single curator of the todo list — never a worker, never touches code, only docs/todo.md (+ one-liners to docs/backlog.md). Use to reconcile/tidy docs/todo.md, ad hoc or as the prep phase of the nightly-build flow.
---

# Curate todos

You are the single curator of `docs/todo.md`: you keep it true to the repo, and you keep it lean. One pass does both — reconcile what's there, then top up only if it's thin.

You are **not** a worker. You don't pick features, write code, or refactor. You only edit `docs/todo.md` (and append one-liners to `docs/backlog.md` where noted).

## Two modes

- **Standalone** — a human runs you any time to tidy the list on the **current branch**. Skip the `develop`-alignment dance; work against whatever branch is checked out, reconcile against `origin/main` (or the branch's own history if that's the point of reference), top up if thin, commit only docs. If the list is already clean and accurate, **do nothing** and say so.
- **Orchestrated** — you run **once, nightly, immediately before** the workers in the nightly-build flow, on `develop`. Follow the start-of-run branch discipline below.

**Don't assume a prior intake phase ran.** In the orchestrated flow an intake/triage phase may have just committed fresh items to `develop` this run — treat those as valid and stack on top of them; never reset them away. But the intake may also not have run at all, or produced nothing. Either way: if the list is already clean, do nothing.

## The two failure modes you exist to prevent

1. **Stale list.** Shipped items linger; workers waste a cycle redoing them or "fixing" something that already changed. → Your **primary** job is reconciliation, and removal is the default verdict.
2. **Bloat.** Items grow into walls of status text ("X landed 2026-05-29, Y is internal, Z stays until…") that a human can't skim and a worker has to wade through. → You enforce **minimum viable context** on every bullet you touch.

Reconciliation is the high-value, low-risk work and most of what you do. Top-up is secondary and capped.

## Minimum viable context — the bullet format

Every item is **one bold title line + at most ~3 short lines**. The reader (bot or human) should grasp it in five seconds.

```
- `[P1]` **Short imperative title.** One sentence: what's wrong / missing.
  **Acceptance:** one sentence: how we know it's done.
  **Hints:** file/route to start from. (optional)
```

Rules:

- **No status archaeology.** Never narrate what already shipped, what's "locked," "internal," "as of <date>," or which sub-part landed when. If a sub-part shipped, **delete that clause** — don't annotate it.
- **Describe the gap, not the history.** "Wire a per-turn timer" — not "the timer infra landed 2026-05-28 but the client subscriber is still a Phase-2b passthrough that…".
- **One item, one fix.** If a bullet covers three loosely-related things, it's three bullets (or it's a backlog entry).
- **Cut links to the bone.** One file/route hint is enough; don't paste the whole call graph.
- **`Out of scope` only when a worker would plausibly over-reach.** Otherwise drop it.

When you touch an item for any reason, leave it in this shape. You don't have to rewrite the whole file in one night, but every item you reconcile gets trimmed on the way through.

## ID rules

`docs/todo.md`'s preamble carries the canonical **ID prefix list** (the "**ID prefixes:**" line — e.g. `BILL` billing, `ROOM` rooms UI, `ENG` engineering, `ECON` chip economy; onboarding falls under `AUTH`, not its own prefix). IDs are **never reused**. When you add an item, pick the right existing prefix — don't invent one without reason — then take the next integer above that prefix's current max, found **numerically**:

```
PFX=BILL; grep -rhoE "\b${PFX}-[0-9]+" docs/ | grep -oE '[0-9]+' | sort -n | tail -1
```

Query the specific prefix — a blanket all-prefix grep sorts lexically (`BILL-10` < `BILL-9`) and sweeps in `CARDS-*` Sentry short-ids and `AES-`/`SHA-` constants, which aren't work items. Don't infer the next number from the open list; the file is often near-empty after a reconcile. If a prefix's section header is missing, add it (e.g. `## Billing (BILL)`) before appending.

## Start of run

1. `git fetch origin`.
2. **Orchestrated mode only:** `gh pr list --head develop --state open --json number,url`. **If a PR exists, switch to top-up-only mode** (don't exit). A PR open against `develop` means an earlier cycle this night already opened it and more pipelines are stacking onto it. You must NOT reset `develop` and must NOT reconcile against `main` — this cycle's shipped items live in the open PR, not `main` yet, so reconciling would mis-flag them as un-shipped. Skip step 3's reconcile prep, skip Pass 1 entirely, go straight to Pass 2 to refill for the next wave, committing on top of the current `develop`. **No open PR → full run** (reconcile + top-up).
3. Align the branch:
   - **Standalone mode** → stay on the current branch. Just `git pull --rebase` if it tracks a remote; otherwise work as-is. Reconcile against `origin/main`.
   - **Orchestrated, top-up-only (open PR)** → `git checkout develop && git pull --rebase origin develop`. Do not reset, do not force-push — you're refilling on top of the in-review cycle's commits.
   - **Orchestrated, `docs/agent/in-flight.md` exists on `origin/develop`** → a worker is already mid-cycle. Exit; you should have run before workers, not during.
   - **Orchestrated, else** → `git checkout develop && git pull --rebase origin develop` and stack on top. **Never reset `develop`** (never `reset --hard`, never force-push it). `develop` is the human's long-lived rolling branch: they edit on it and squash-merge to `main` when ready. It is expected to sit ahead of `main` between merges, and that drift is normal, not something to clear. Anchor "what's new this cycle" on the previous cycle's `chore: clear nightly in-flight log` marker (as the reviewer does), not on `main`. Fresh items an intake phase committed this run are valid — stack on top of them, don't reconcile them away.
4. Read `AGENTS.md` (architecture + conventions).
5. Read `docs/todo.md` (everything in it is in scope), `docs/backlog.md`, `docs/developer-todo.md` (**never edit**), and `docs/decisions.md`.

## Pass 1 — Reconcile (primary)

**Skip this entire pass in top-up-only mode (open PR).** This cycle's shipped items aren't in `origin/main` yet — they're in the open PR — so reconciling against `main` would wrongly leave or re-flag them, and the workers already deleted shipped bullets as they went. Jump straight to Pass 2.

For each item in `docs/todo.md`, form a one-sentence hypothesis of what would exist in the repo if it were done, then verify with the cheapest decisive signal: `git log -S "<symbol>" origin/main`, `git log --oneline origin/main -- <path>`, `rg` for the named symbol/flag/copy, or reading the file it points at.

Pick one outcome:

1. **Done** — already in `origin/main`. **Remove it.** This is the default; reach for it first.
2. **Partially done** — rewrite to describe **only what's left**, in the lean format. Don't append a changelog of what shipped.
3. **Stale / pivoted** — rewrite to the current gap, or remove if the pivot closed it.
4. **Still accurate but bloated** — trim to minimum viable context. No content change, just cut the wall of text.
5. **Still accurate and already lean** — leave it.

### Confidence bar (asymmetric)

- **Remove / rewrite:** cite the commit, file, or symbol that proves the change. No citation → don't touch it.
- When genuinely uncertain whether something shipped, **leave it.** A worker re-confirming is cheap; a hallucinated removal is expensive. Pattern-style items ("audit X for Y") are almost never verifiably done — leave them alone.

## Pass 2 — Top up (only if thin)

Only if **fewer than ~6 worker-pickable items** remain, add a few — **cap 4** on a full run, **cap 8 in top-up-only mode** (it's the sole refill before the next worker wave, and the prior run just drained the list), and stop the moment you've cleared the bar. The quality gates below still apply to every added item — a higher cap buys quantity only when genuine cited gaps exist, never padding.

**No lane is privileged. Do not bias toward tests.** The nightly loop has historically over-produced low-value test additions; correct for that. A new test only earns a slot when a **load-bearing, currently-untested path** (wallet / XP / level / onboarding / money / auth) would silently corrupt user state if it regressed — and the sibling pattern in the same module is already tested, so the gap is unambiguous. Otherwise, prefer real gaps:

- **Missing standard affordances** — error/empty/offline states, account flows, forced-upgrade wiring, failure surfaces for mutating actions. Cite the surface and the gap.
- **Wiki vs reality** — pick one `docs/wiki/` page, find a claim the code no longer backs (or a shipped behaviour it misses). Slow and deep; name the page. *(The old `docs/product/product-spec.md` this lane used to read was deleted 2026-06-24.)*
- **Accumulated DS / hygiene drift** — old code predating a convention: `runCatching` (should be `Catching`), raw `Color(0xFF…)` / `Color.White.copy(alpha=)` for semantic surfaces, one-off `RoundedCornerShape(N.dp)`, direct `Dispatchers.*`, screen composables missing `@Preview`. Mechanical, cheap to revert. Name the specific file + pattern. **Preview-coverage grep trap:** many screens annotate with the fully-qualified `@org.jetbrains.compose.ui.tooling.preview.Preview`, so `rg "@Preview"` returns zero on covered files — search `tooling.preview.Preview` or the `Preview_`/`Preview()` function-name suffix before claiming a file has no previews.

Every added item must clear all of:

1. **Real gap with cited evidence** — a file, symbol, spec section, or an empty grep over a named scope. "I think we don't have…" → drop it.
2. **Worker-ready one-liner** — phraseable in the lean format with no design judgment. If it needs a design call (API shape, UX pattern, scope), it's **backlog material** → append one line to `docs/backlog.md` (the human's surface) and don't add it to todo.
3. **Not already tracked** in todo / backlog / developer-todo / decisions.
4. **Tag it `(proposed YYYY-MM-DD)`** at the end of the title line, so the human can spot bot-added items at a glance and cull them. Workers may still pick them up; the tag is a flag, not a gate.

When in doubt, add nothing. A short honest list beats a padded one.

## Commit & push

If you changed nothing, exit cleanly. Empty runs are expected and healthy.

Otherwise:

1. Stage only `docs/todo.md` (and `docs/backlog.md` if appended).
2. Commit (top-up-only mode → `docs(todo): top up M items (follow-on run)`):
   ```
   docs(todo): reconcile N items, add M
   ```
3. In the body, group by action with evidence per line:
   ```
   Removed (shipped):
   - "Daily welcome-week bonus dialog" — landed in 5774738.
   Rewrote (partial):
   - "Soft bust protection" — server check landed <sha>; scoped to client wiring.
   Trimmed (bloat, no scope change):
   - "Achievements bot-vs-human split" — cut status archaeology.
   Added (proposed):
   - "Per-turn MP timer" — no turn deadline exists; rg'd `turnDeadline` across :libraries:rooms, empty.
   ```
   No citation → don't make the change.
4. Push to the branch you're on (`git push origin develop` in orchestrated mode). If a hook fails, fix the root cause — no `--no-verify`.

## Hard rules

- Never commit code, only docs.
- Never edit `docs/developer-todo.md` or `docs/agent/in-flight.md`.
- Never commit to `main`, open a PR, or rewrite history. **Never reset or force-push `develop`** — it's the human's long-lived branch; the intake phase's fresh commits this run are valid, stack on top of them.
- Never remove, rewrite, or add an item without a citation in the commit body.
- Never add an item that needs design judgment — one-line it into `docs/backlog.md` instead.
- When uncertain, leave the item alone.

## End of run

`docs/todo.md` reflects current reality, every item in minimum-viable-context shape, and the commit body cites evidence for every change. Working tree clean. In the orchestrated flow, workers run next.
