# In-flight log

Ephemeral handoff notes from workers to the reviewer. Reviewer deletes when the PR opens/updates.

## docs(wiki): correct sibling-bus semantics in client-patterns (ENG-24)

**Problem:** The client-patterns wiki claimed `SessionRejectionBus` shares `ShopDeepLinkBus`'s conflated / consume-once shape and placed both buses in `:libraries:cards` — both wrong.
**Approach:** Rewrote the "Sibling buses" section around the real impl (non-replaying `MutableSharedFlow`, buffer 8, `DROP_OLDEST`, plus `rejectionEpoch`), explained *why* the two delivery semantics differ (lazy consumer vs boot-time consumer), and added `AccessDeniedBus` since its kdoc explicitly names it the mechanically-parallel sibling. Fixed the Key-files module paths, including the stale `ShopViewModel.observeBus` reference (the VM collects `scrollRequests` in `init {}`).
**Reviewer notes:** None.

## feat(telemetry): instrument the full Part A app-event taxonomy (ENG-18, 4 commits)

**Problem:** The `logEvent` → `GrafanaLogTree` pipe (plan PR 1) shipped with only starter events; the matchmaking/onboarding/monetization/reliability funnels were dark.
**Approach:** Shipped the plan's PR 2 sweep end-to-end across three commits (matchmaking funnel; game/connection/reachability; onboarding/monetization/usage) plus the `docs/wiki/app-events.md` registry. Judgement calls, per the plan's conventions: (1) `net.backend_unreachable` lives in the shared `NetworkCall.logFailure` choke point and excludes `ResponseException` — an HTTP status is proof of reachability; the rejected alternative was per-repo instrumentation. (2) `conn.recovered` fires on the first *decoded frame* after an outage, not the handshake, so half-open sockets (the MP-16-era signature) don't count as recoveries. (3) `shop.viewed` fires from the screen entry point without an `entry_point` attr — the tab root is arg-less by routing rules, so entry attribution comes from the preceding event in the session (documented in the registry); the rejected alternative was threading an entry hint through the deep-link bus. (4) `onboarding.abandoned` is best-effort from `onCleared` (suppressed once the flow routed Home); process kills are covered by step_viewed-without-completed queries.
**Reviewer notes:** Funnel emission is test-pinned for matchmaking (`PublicSearchingViewModelTest`, new CapturingLogTree section); the other surfaces ride existing module tests plus the GrafanaLogTree pipe tests — per-event assertions there would be redundant with the shared extension. `game.started`/`game.ended` fire for solo bots too (mode attr separates them). Events are logcat-visible now but reach Loki only after the owner pastes the token (ENG-18's remaining verification slice, rewritten in todo).
**Deferred:**
- `matchmaking.search_started` `entry=deep_link` — the invite-link join path doesn't route through either instrumented VM; worth adding when the deep-link funnel matters. Nothing filed (small, registry notes only public/private_code exist).
- Plan PR 3 (Warn+ forwarding behind a flag) + PR 4 (dashboards + alert) — already tracked in the ENG-18 bullet's hints.

## docs(todo): record Loki verification evidence on ENG-18/ENG-19 gate

**Problem:** The orchestrator asked whether the ENG-18 verification drills (and downstream ENG-19 dashboard) could be run tonight via the authenticated Grafana MCP instead of waiting on the owner token.
**Approach:** Ran the check directly against the stack: `list_loki_label_values(service_name)` on `grafanacloud-logs` returns only `cards-server`, and `query_loki_stats({service_name="cards-client"})` is zero streams/chunks/entries over both 7d and 30d windows. `GrafanaCloud.OTLP_BASE_URL/INSTANCE_ID/LOGS_WRITE_TOKEN` are still blank on develop, so no build has ever shipped an event. Updated both todo bullets to carry this evidence instead of the previous worker's untested "judged gated" framing.
**Reviewer notes:** The gate is genuinely owner-shaped, not worker-avoidable: the logs:write token is a grafana.com Cloud Access Policy token (minted in the Cloud console — the MCP proxies only the instance API, which cannot create access policies), and the plan's drills (correlation query, kill-switch, offline/airplane-mode batch flush) all require a debug run of a build carrying the pasted credentials. Deliberately did NOT pre-build the ENG-19 dashboard against empty data: the plan defers dashboards to PR 4 because the exact structured-metadata key Grafana derives for OTLP `eventName` is unconfirmed until the first real record lands — panels authored now would be unverifiable guesswork needing a second pass anyway. Owner-token item is already tracked in developer-todo (untouched).
