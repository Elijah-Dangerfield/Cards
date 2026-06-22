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

## What counts as "feedback"

In-app feedback is captured as a Sentry event whose message is exactly **`User feedback`** or **`Bug report`** (see `AppTelemetry.captureUserFeedback` — a `captureMessage` carrier event with the user's comment attached, tagged `feedback_type`). The user's typed comment and email ride on the event's User Feedback / comment.

## Procedure

### 1. List unresolved feedback

```
search_issues(
  organizationSlug='elijah-dangerfield',
  projectSlugOrId='cards',
  query='is:unresolved (message:"User feedback" OR message:"Bug report")',
  sort='new',
)
```

Cross-check each candidate's event id against `docs/agent/feedback-log.md`; **skip anything already logged.** If nothing new, write "no new feedback" to the run summary and stop.

### 2. Pull the report + its identifiers

For each new feedback issue, `get_issue_details` (and `get_sentry_resource` with `resourceType='breadcrumbs'` on the event). Extract:

- The user's **comment** (and `error_code` for bug reports), email, username.
- **`session_id`** tag, `install_id`, `user.id`, `route`, `environment`, `release`, and the event **timestamp** (this anchors the backend time window).

If `session_id` is absent (older client build before the correlation work shipped), fall back to `user.id` + a tight time window and note the degraded correlation in the todo.

### 3. Reconstruct the session — frontend (Sentry)

- The feedback event's **breadcrumbs** are the frontend trail (route changes, logged events) leading up to the report.
- `search_events(dataset='errors', query='session_id:<id>', statsPeriod='24h')` for any crashes/errors in the same session. Also try `user.id:<id>` to widen.

### 4. Reconstruct the session — backend (Grafana)

Use a window of ~`event_timestamp ± 15m` (RFC3339). The session almost always starts minutes before the report.

- **Traces (Tempo):**
  `tempo_traceql-search(datasourceUid='grafanacloud-traces', query='{ .session_id = "<id>" }', start=..., end=...)`
  Inspect slow or error spans with `tempo_get-trace`. Endpoints like `/v1/...` map to features.
- **Logs (Loki):**
  `query_loki_logs(datasourceUid='grafanacloud-logs', logql='{service_name="cards-server"} | session_id="<id>"', startRfc3339=..., endRfc3339=...)`
  Look for warnings/errors/stack traces in the window.

### 5. Determine root cause and decide

Synthesize comment + frontend trail + backend traces/logs into a concrete root cause. Optionally use `analyze_issue_with_seer` for code-level analysis on a linked crash. Then pick one:

**(a) Actionable → file a todo.** Append to `docs/todo.md` in the house format (see `docs/agent/todo-maintainer.md` — one bold title + ≤3 lines, a priority tag, no status archaeology):

```
- `[P1]` **Short imperative title of the fix.** One sentence: what's wrong (from the report + telemetry).
  **Acceptance:** one sentence: how we know it's fixed.
  **Hints:** the file/route/span that points at the cause; Sentry issue URL; session_id <id>.
```

Priority guide: crash / data-loss / blocks core MP → `[P0]`; real broken behavior → `[P1]`; polish / rare edge → `[P2]`. If the work is large or needs a product call, put a one-liner in `docs/backlog.md` instead and reference it.

**(b) No action needed → resolve.** Praise-only, duplicate of an existing todo/issue, not-reproducible-and-not-a-defect, or user error. Record why in the ledger.

### 6. Close the loop

For **every** feedback handled, append to `docs/agent/feedback-log.md`:

```
- <date> · <event_id> · session <id> · <"todo: <title>" | "no-action: <reason>" | "backlog"> · <Sentry issue URL>
```

Then resolve in Sentry so it leaves the unresolved queue:

- **If `SENTRY_AUTH_TOKEN` is set in the env**, resolve via REST:
  `curl -sS -X PUT "https://us.sentry.io/api/0/organizations/elijah-dangerfield/issues/<issueId>/" -H "Authorization: Bearer $SENTRY_AUTH_TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}'`
  (Optionally POST a comment to `/comments/` with the disposition.)
- **If no token**, the ledger is the source of truth — the search in step 1 still finds the issue, but the event-id check in step 1 skips it. Note in the run summary that Sentry status wasn't flipped (set up `SENTRY_AUTH_TOKEN` to enable it).

### 7. Run summary

End with a short summary: N feedback processed, M todos filed (with titles), K resolved no-action, any that need a human (ambiguous, product decision, couldn't reproduce).

## Guardrails

- **Idempotent:** the ledger check in step 1 is mandatory — never double-file a todo for the same event.
- **Read-mostly on infra:** only writes are `docs/todo.md`, `docs/backlog.md`, `docs/agent/feedback-log.md`, and (with a token) the Sentry issue status. No code changes — fixing the bug is a separate worker's job off the todo you file.
- **Don't invent telemetry:** if Tempo/Loki return nothing for the session_id, say so (likely a dev build that didn't reach the cloud backend, or pre-correlation client) rather than guessing a backend cause.
- **One feedback can become at most one todo.** Multiple reports of the same root cause → one todo, the rest resolved as duplicate pointing at it.
