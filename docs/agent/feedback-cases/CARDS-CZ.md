# CARDS-CZ — Grafana rejects log records with 400

**Signal:** Sentry, no user feedback. 5 events 2026-10-23T22:03:26–28Z, `cards@0.6.0+1308`,
iOS 27.0.1, iPhone19,3, `store-ios-release`. One install `f6d7a420-38d1-4e92-8efc-b1700065fb9e`,
one session `c90534df-1174-4c31-9825-ddb466e39fbe`, route `HomeRoute`.
Todo: **ENG-82 [P1]**.

## What is proven

- The message is our own, added in 0.6.0 for ENG-80: a 4xx from the OTLP gateway is logged and the
  batch settled rather than retried forever. Batch sizes 1, 1, 4, 2, 2 — about ten records dropped.
- Breadcrumbs interleave `warning | http` with each rejection.
- **The pipe is not dead.** The same session has 8 records in Loki (`app.launched`,
  `app.previous_run`, `shop.viewed`, `app.backgrounded`), all `severity_number: 9`. So a subset of
  records is rejected while the rest are accepted.
- **Not the dropped-SKU path.** Checked and ruled out: CARDS-8V is install `589fa27b`, iOS 26.5,
  `0.5.0` — a different device, OS and build from this one.
- iOS 0.6.0 had only ~5 `app.foregrounded` in the 3 days to 2026-10-24 (it was still in review), so
  one affected install is a large share of the exposed population, not an outlier.

## What is not known

The response body is discarded, so Grafana's objection is unreadable. Two candidates fit:

1. **Oversized attribute value.** `klogForwardingEnabled` defaults true, so Warn+ plain logs ship
   with `exception_message`. On iOS those are `DarwinHttpRequestException` NSError dumps running to
   many hundreds of characters with nested UserInfo. The `warning | http` breadcrumb before each
   rejection is consistent with this.
2. **Out-of-window timestamps.** Records replayed from the durable disk buffer carry their original
   `timeUnixNano`; Loki rejects samples older than its accepted window.

Neither is confirmed and the fix differs between them, which is why ENG-82 leads with logging the
body rather than with a guess.

## Working theory

Candidate 1 is favoured only because the breadcrumb pairing is suggestive. Do not act on that alone.

## Fix shape

`OtlpJsonLogRecordExporter.export` already branches on `isPermanentRejection()`; add a truncated
`response.bodyAsText()` to that log line. Separately, cap attribute value length in
`putAttributeList` regardless of outcome. Reconsider ERROR for a routine drop — it is what routed
this to Sentry and to the owner's inbox.
