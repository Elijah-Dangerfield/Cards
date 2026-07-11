# In-flight log

Ephemeral handoff notes from workers to the reviewer. The reviewer folds these
into the PR body and deletes this file.

## fix(networking): quiet room sockets no longer die at 15s (MP-32)

**Problem:** Every quiet MP room socket dropped at almost exactly 15s and reconnected in ~450ms, strobing the "lost connection" banner through MP games (CARDS-9A).
**Approach:** The triage theory (server pings disabled) was wrong — the server has had `pingPeriod=15s` since the socket shipped. Actual cause, proven red-first in a new `:apps:integration` test: the debug-only Wiretap WS inspector wraps the raw OkHttp session in a plain `WebSocketSession`, which defeats Ktor's "engine session is already a `DefaultWebSocketSession`" fast path, so Ktor starts its own plugin-level pinger (`pingIntervalMillis=15s` in `NetworkClientImpl`) — and OkHttp's write loop throws `UnsupportedFrameTypeException` on the first outgoing `Frame.Ping`, killing the socket at exactly 15s. Fix: keepalive is now per-engine via a new `installWebSocketKeepalive` expect/actual — Android uses OkHttp's native `pingInterval` (works below any wrapper, and release builds gain real client-side keepalive they never had), iOS keeps the plugin-level pinger (Darwin maps `Frame.Ping` to `NSURLSessionWebSocketTask.sendPing`). Alternative rejected: dropping the client pings entirely and leaning on server pings alone — loses client-side dead-peer detection.
**Reviewer notes:** This means release/TestFlight builds were never affected (wiretap is stripped there) — the bug was dev/debug builds only, contrary to the todo's "affects every quiet MP table on both platforms". The regression test (`SocketKeepaliveTest`) deliberately runs ~19s of real wall-clock (the failure is a real-time ping-scheduler race; unreachable under virtual time) and mimics wiretap with a pass-through raw-session wrapper because the real plugin's DI can't bootstrap under host-JVM JUnit.
**Deferred:** Banner debounce (only show "lost connection" after a few hundred ms of downtime) — not needed once the keepalive is honest; noted here in case the reviewer disagrees. Nothing filed.
