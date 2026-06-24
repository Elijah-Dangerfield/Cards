---
name: feedback-triage
description: Triage unresolved in-app user feedback. Fetch each feedback report from Sentry, pull the full session story across frontend (Sentry) and backend (Grafana Tempo/Loki) via session_id, find the root cause, then either file a docs/todo.md item or resolve it as no-action. Use when asked to "triage feedback", "process feedback", "check user reports", or on a nightly schedule.
---

# Feedback triage

Turn a tester's in-app feedback into either a worker-pickable todo or a closed no-action item, with the root cause already investigated. Designed to run unattended nightly, but works interactively too.

## Fixed coordinates

These are stable for this repo — don't rediscover them every run, but reconfirm if a call 404s.

- **Sentry:** org `elijah-dangerfield`, project `cards`, region `https://us.sentry.io`.
- **Grafana datasources:** Tempo `grafanacloud-traces`, Loki `grafanacloud-logs`.
- **Correlation key:** `session_id` (a per-session UUID) ties a session's frontend and backend telemetry. Also available: `install_id` (stable per install), `user.id`. See [reference: session correlation](../../../docs/wiki) and the `SessionIdProvider` in `:libraries:networking`.
- **Todo destination:** `docs/todo.md` (worker-pickable; strict format — see step 5). Larger/blurrier work → one-liner in `docs/backlog.md`.
- **Processed ledger:** `docs/agent/feedback-log.md` — append-only record of every feedback already handled (keyed by Sentry event id), so reruns skip it.
- **Case files:** `docs/agent/feedback-cases/<event_id>.md` — one per investigated report, written by step 5b below. Bundles bug description, IDs (including opponents recovered from Loki), reporter client log excerpt, client state at submit time, server activity, and the working theory. The todo's `Hints:` line links here so a worker can pick up the full context cold.

## What counts as "feedback"

In-app feedback is captured as a Sentry event whose message is exactly **`User feedback`** or **`Bug report`** (see `AppTelemetry.captureUserFeedback` — a `captureMessage` carrier event with the user's comment attached, tagged `feedback_type`). The user's typed comment and email ride on the event's User Feedback / comment.

## Procedure

### 1. List unresolved feedback

`search_issues` does **not** support boolean `OR`, so run two queries and union the results:

```
search_issues(organizationSlug='elijah-dangerfield', projectSlugOrId='cards', query='is:unresolved message:"User feedback"', sort='new')
search_issues(organizationSlug='elijah-dangerfield', projectSlugOrId='cards', query='is:unresolved message:"Bug report"', sort='new')
```

Cross-check each candidate's event id against `docs/agent/feedback-log.md`; **skip anything already logged.** If nothing new, write "no new feedback" to the run summary and stop.

Each feedback report is its own issue: the carrier event is fingerprinted per-feedback (`beforeSend` keys off the `feedback_event` tag — see `AppTelemetry`), so one issue = one report even though the title is always "User feedback" / "Bug report". (Reports filed before that shipped grouped into a single issue — if you hit one with many events, triage each event by its own `session_id`.)

### 2. Pull the report + its identifiers

For each new feedback issue, `get_issue_details` (and `get_sentry_resource` with `resourceType='breadcrumbs'` on the event). Extract:

- The user's **comment** (and `error_code` for bug reports), email, username.
- **`session_id`** tag, `install_id`, `user.id`, `route`, `environment`, `release`, and the event **timestamp** (this anchors the backend time window).
- MP scope tags when present: **`room_code`**, **`seat_index`**, **`hand_number`**, **`opponent_user_ids`** (comma-joined). These are stamped by `RemotePokerSessionFactory` whenever the reporter is in an MP game — absent = report filed outside MP, so skip MP-specific recovery in step 4.
- **Attachments** on the carrier event: `session-log.txt` (always, when the ring buffer had anything), `client-state.json` (MP only — the latest `GameState` at submit time). Pull both via the Sentry attachments API. The log buffer now includes inbound WS frames (`recv game_state …`, `recv ActionTaken …`, `apply StateSnapshot …`) — read it for what the client was actually told, not just what it did.

If `session_id` is absent (older client build before the correlation work shipped), fall back to `user.id` + a tight time window and note the degraded correlation in the todo.

**Classify the report first — two kinds come through this channel:**

- **End-user feedback / bug report** — someone describing a problem or experience. These get the full investigation (steps 3–4) before you decide.
- **Owner change-request / self-note** — the project owner uses the in-app feedback box to jot things he wants changed ("change the X", "add Y", "I want Z", a design/feature tweak). These are *directives*, not problems to reproduce. Tell-tales: first-person imperative or "I want…" phrasing, a feature/design ask rather than a symptom. When a report reads this way, **skip the telemetry dig** (steps 3–4) — there's no session to reconstruct — and turn it straight into a todo (step 5a) or, if larger/fuzzier, a backlog one-liner. Never resolve a directive as "no-action / not reproducible"; it's a wanted change. When unsure which kind it is, treat it as end-user feedback and investigate.

### 3. Reconstruct the session — frontend (Sentry)

- The feedback event's **breadcrumbs** are the frontend trail (route changes, logged events) leading up to the report.
- `search_events(dataset='errors', query='session_id:<id>', statsPeriod='24h')` for any crashes/errors in the same session. Also try `user.id:<id>` to widen.

### 4. Reconstruct the session — backend (Grafana)

Use a window of ~`event_timestamp ± 15m` (RFC3339). The session almost always starts minutes before the report.

- **Traces (Tempo):**
  `tempo_traceql-search(datasourceUid='grafanacloud-traces', query='{ .session_id = "<id>" }', start=..., end=...)`
  Matches the whole trace tree — HTTP root spans *and* gameplay child spans (`submit_intent`, `engine.apply_intent`, …) all carry `session_id` via baggage. Inspect slow or error spans with `tempo_get-trace`. Endpoints like `/v1/...` and span names map to features. For multiplayer issues, also pivot on `room.code` / `session.id` (the game-room session, distinct from the app `session_id`) once you've found the trace.
- **Logs (Loki):**
  `query_loki_logs(datasourceUid='grafanacloud-logs', logql='{service_name="cards-server"} | session_id="<id>"', startRfc3339=..., endRfc3339=...)`
  Look for warnings/errors/stack traces in the window. **The only stream labels are `service_name` and `deployment_environment`** — `session_id`, `install_id`, `room_code`, `trace_id` are **structured metadata**, so match them with the label-matcher pipe (`| session_id="<id>"`), **not** a line filter (`|= "<id>"`) — a line filter only scans the log message text and silently returns nothing for these fields. For multiplayer, `| room_code="<CODE>"` (uppercase — it's normalized server-side) is the best pivot: it tags every `/v1/rooms/{code}/…` route's logs including the socket handler, whereas `session_id` on a long-lived room coroutine is just whoever's socket currently holds it (a bot has none). Seeded into MDC in `apps/server/.../plugins/Observability.kt`.
- **MP opponent recovery (room_code present).** Run a second Loki query to enumerate every player that connected to the room during the session window — gives you the opponents' `session_id`s without any room-state lookup, and works even if the room has since closed:
  `query_loki_logs(datasourceUid='grafanacloud-logs', logql='{service_name="cards-server"} | room_code="<CODE>" |= "Socket connected"', startRfc3339=..., endRfc3339=...)`
  Each match has the connecting `user_id` in the log text and the connecting `session_id` in structured metadata. Collect the distinct `(user_id, session_id)` pairs; that's the table. Then re-run the per-`session_id` Sentry/Tempo/Loki queries above for each opponent — their parallel story is what you need for "the other player said X happened but I saw Y" reports.

### 5. Determine root cause and decide

Synthesize comment + frontend trail + backend traces/logs into a concrete root cause. Optionally use `analyze_issue_with_seer` for code-level analysis on a linked crash.

**Then, before filing anything**, for any report you actually investigated (i.e. not an owner change-request resolved straight to a todo) write a **case file** at `docs/agent/feedback-cases/<event_id>.md`. One file per investigated report, written exactly once — overwrite is fine if you re-run on the same event. Skip the directory check; `mkdir -p docs/agent/feedback-cases` is cheap. Template:

```
# Feedback case <event_id>

- **Sentry issue:** <issue URL>
- **Reported:** <event timestamp> · <route> · <environment> · <release>
- **Disposition:** <todo: "<title>" | backlog | no-action: "<reason>">

## Bug description
> <user comment, verbatim; include email if present>

## IDs
- user: <user.id> (<username/email>)
- session: <session_id>
- install: <install_id>
- MP context (omit section if no room_code):
  - room: <room_code>
  - reporter seat: <seat_index> · hand: <hand_number>
  - opponents (from Sentry tag `opponent_user_ids`): <ids>
  - opponents discovered via Loki (room_code query in step 4): <(user_id, session_id), …>

## Reporter client log
~30 lines of `session-log.txt` around the submit time (the new `recv …` / `apply …` lines included). Trim aggressively — paste the most relevant slice, not the whole buffer. If the buffer was empty/absent, say so.

## Client state at submit
For MP reports with `client-state.json`: paste the salient fields (handNumber, street, pot, acting seat, the reporter's seat + holeCards, opponents' seats with chips). Skip the full deck. If absent (non-MP report or release build without an active MP session), say so.

## Server activity
- Tempo: 1-line summary of the most relevant span(s) found by the `session_id` / `room.code` queries. Include trace id(s).
- Loki: 3-10 lines that explain or contradict the user's report (warnings/errors first, then context). Skip if Tempo+Loki returned nothing for this session — note "no backend telemetry, likely <reason>".

## Working theory
2-5 sentences. What happened, why, and what the fix is. If it needs a product call or you couldn't reconcile the frontend/backend evidence, say so honestly — the todo's confidence and the human reviewer depend on this not being padded.
```

Keep case files terse; they are bug-investigation notes, not narratives. The point is one place a worker (or you, on a rerun) can pick up the full story cold.

Then pick a disposition:

**(a) Actionable → file a todo.** Append to `docs/todo.md` in the house format (see `docs/agent/todo-maintainer.md` — one bold title + ≤3 lines, a priority tag, no status archaeology). Include the case-file path in `Hints:` so a worker reads it before touching code:

```
- `[P1]` **Short imperative title of the fix.** One sentence: what's wrong (from the report + telemetry).
  **Acceptance:** one sentence: how we know it's fixed.
  **Hints:** the file/route/span that points at the cause; case `docs/agent/feedback-cases/<event_id>.md`; Sentry issue URL.
```

Priority guide: crash / data-loss / blocks core MP → `[P0]`; real broken behavior → `[P1]`; polish / rare edge → `[P2]`. If the work is large or needs a product call, put a one-liner in `docs/backlog.md` instead and reference both the case file and Sentry issue. **Owner change-requests** (classified in step 2) skip the case file — they're directives, not investigations — and land straight in `docs/todo.md` (or backlog); priority by impact, default `[P2]` for a pure preference tweak.

**(b) No action needed → resolve.** Praise-only, duplicate of an existing todo/issue, not-reproducible-and-not-a-defect, or user error. Still write the case file so a future rerun has the full reasoning — record the disposition's *why* in the case file's "Working theory" section, not just the ledger.

### 6. Close the loop

For **every** feedback handled, append to `docs/agent/feedback-log.md`. Include the case-file path when one was written (omit only for owner change-requests, which skip the case file):

```
- <date> · <event_id> · session <id> · <"todo: <title>" | "no-action: <reason>" | "backlog"> · <Sentry issue URL> · case docs/agent/feedback-cases/<event_id>.md
```

Then resolve in Sentry so it leaves the unresolved queue. Resolve the token from
the env, falling back to the macOS Keychain (so unattended runs work without a
plaintext token on disk):

```
TOKEN="${SENTRY_AUTH_TOKEN:-$(security find-generic-password -s cards-sentry-auth-token -w 2>/dev/null)}"
```

- **If `$TOKEN` is non-empty**, resolve via REST (never echo the token):
  `curl -sS -X PUT "https://us.sentry.io/api/0/organizations/elijah-dangerfield/issues/<issueId>/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}'`
  (Optionally POST a comment to `/comments/` with the disposition.)
- **If `$TOKEN` is empty**, the ledger is the source of truth — the search in step 1 still finds the issue, but the event-id check skips it. Note in the run summary that Sentry status wasn't flipped (store the token in the keychain item `cards-sentry-auth-token` to enable it).

### 7. Run summary

End with a short summary: N feedback processed, M todos filed (with titles), K resolved no-action, any that need a human (ambiguous, product decision, couldn't reproduce).

## Guardrails

- **Idempotent:** the ledger check in step 1 is mandatory — never double-file a todo for the same event.
- **Read-mostly on infra:** only writes are `docs/todo.md`, `docs/backlog.md`, `docs/agent/feedback-log.md`, and (with a token) the Sentry issue status. No code changes — fixing the bug is a separate worker's job off the todo you file.
- **Don't invent telemetry:** if Tempo/Loki return nothing for the session_id, say so (likely a dev build that didn't reach the cloud backend, or pre-correlation client) rather than guessing a backend cause.
- **One feedback can become at most one todo.** Multiple reports of the same root cause → one todo, the rest resolved as duplicate pointing at it.
