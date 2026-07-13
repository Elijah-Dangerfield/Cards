# CARDS-94 — iOS fatal crash: "TLS sessions are not supported on Native platform"

- **Sentry:** https://elijah-dangerfield.sentry.io/issues/CARDS-94
- **Filed as todo:** ENG-28
- **Signal:** `kotlin.IllegalStateException: TLS sessions are not supported on Native platform.`
- **Severity:** fatal / unhandled (`mechanism: nsexception`, `handled: no`, `level: fatal`)
- **Volume:** 6 events, 1 user, first/last seen 2026-07-11 18:22Z → 21:14Z
- **Where:** platform `cocoa` (iOS), `environment: dev-ios-debug`, `build_type: simulator`,
  `commit_branch: develop`, `commit_sha: aeb0ff5a8206`, `route: HomeRoute`, iPhone17,5 simulator,
  iOS 26.1. `session_id: bb73f742-0cc4-4018-828f-8f2dd576e062`.

## What the crash tells us

The message is emitted by a Ktor **CIO / native-socket** engine when it is asked to open a TLS
(HTTPS/WSS) connection on Kotlin/Native — the CIO engine has no TLS on Native. So *some* iOS code
path is issuing an HTTPS/WSS request through a TLS-incapable engine instead of the Darwin engine
(NSURLSession), and the resulting exception is thrown on a background worker (`Worker::processQueueElement`
/ `WorkerExecuteAfterLaunchpad` + coroutine `resumeWith` frames) where nothing catches it → process abort.

This is not simulator-specific: "not supported on Native platform" is a Kotlin/Native limitation of
that engine, so a real iOS device on the same code path would crash identically. It is also not
build-type gated — Ktor engine selection does not depend on debug vs release.

## Root-cause investigation (evidence thin — native stack is stripped)

The full native stack is `<unknown>` frames, so the exact call site is **not** pinned from Sentry alone.
Static inspection of the client:

- Every client module wires its engine per-target correctly: `androidMain → ktor-client-okhttp`,
  `iosMain → ktor-client-darwin` (networking/impl, cards/impl, telemetry/impl, identity/impl).
- `ktor-client-cio` is declared **only** in `apps/server` (JVM) — a static grep finds no CIO
  dependency on any iOS source set or in the version catalog beyond that. So CIO should not be on the
  iOS classpath, yet the runtime error is a CIO/native-TLS error. Either (a) an engine-less
  `HttpClient { }` factory is resolving to a TLS-incapable engine at runtime on Native, or (b) a
  transitive dependency drags `ktor-client-cio` into the iOS binary. Confirm with the iOS build's
  actual dependency graph, not just the first-party Gradle files.
- Engine-**less** `HttpClient { }` factories (no explicit engine arg) — the prime suspects, since a
  no-arg factory relies on runtime engine auto-resolution:
  - `libraries/telemetry/impl/.../GrafanaAppEvents.kt:157` `grafanaHttpClient()` — used by the
    OTLP log exporter (`GrafanaAppEvents.kt:65`) that POSTs to the HTTPS Grafana Cloud endpoint on a
    background thread. Matches the "background worker + HTTPS" shape of the crash closely.
  - `libraries/networking/impl/.../NetworkClientImpl.kt:56` and `:62`.

**Leading hypothesis (confidence: medium):** an engine-less `HttpClient { }` (most likely the
telemetry OTLP exporter's `grafanaHttpClient()`) resolves to the CIO/native engine on iOS instead of
Darwin and crashes on the first HTTPS flush. The fix direction is to make every iOS HTTP/WS client use
the Darwin engine explicitly (pass the engine into `HttpClient(<engine>) { }` rather than relying on
auto-resolution) and to audit the iOS dependency graph for a stray `ktor-client-cio`.

## Backend correlation

None expected: this is a client-side networking/telemetry crash on a dev build, and the failure is the
outbound request never completing TLS — there is no server span to correlate. No cards-server
error/fatal logs in the surrounding 24h window.

## Suggested fix / acceptance

Every iOS HTTP and WebSocket client uses the Darwin (NSURLSession) engine; no code path can resolve to
a TLS-incapable native engine. Reproduce by exercising the telemetry OTLP flush (and any WSS connect)
on an iOS target and asserting no `IllegalStateException: TLS sessions are not supported on Native
platform`.
