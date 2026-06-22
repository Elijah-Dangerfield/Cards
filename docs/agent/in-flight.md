# In-flight

Handoff log for this cycle. Reviewer reads it when writing the PR, then deletes it.

> The first two blocks (`feat(server): emit room_code …`, `feat(feedback): 500-char cap …`) were committed by an earlier interrupted worker run this cycle but never pushed and never logged — I pushed them at the start of this run and reconstructed their blocks from the commit messages so they're documented.

## feat(server): emit room_code as Loki structured metadata on room routes

**Problem:** MP triage during the 2026-06-22 feedback batch had to brittle-line-grep Loki because room-route logs carried no room code; a long-lived socket coroutine's `session_id` is just whoever currently holds it (a bot has none), so it can't pivot a whole room.
**Approach:** Parse the room code from the request path on room routes, uppercase it to match the handlers' normalized code + the span `room.code` attribute, and seed it into the CallLogging MDC. The OTel logback appender already forwards all MDC keys (`captureMdcAttributes=*`), so it lands as Loki structured metadata with no logback change. Chose structured metadata over a stream label because a room code is unbounded high-cardinality and a Loki label would explode the index — `{service_name="cards-server"} | room_code="<CODE>"` stays cheap. Also corrected the feedback-triage skill's Loki guidance (these correlation fields are matched with the label-matcher pipe `| session_id=`, not a line filter `|=` which silently matches nothing).
**Reviewer notes:** Pre-existing commit reconstructed from its message — I did not re-verify it at runtime this cycle. The MDC-key forwarding assumption (`captureMdcAttributes=*`) is the load-bearing part; worth a glance.

## feat(feedback): 500-char cap in release, uncapped in debug

**Problem:** The in-app feedback box capped messages at 200 chars — too tight for the owner to paste behavior notes / repro steps (feedback CARDS-8).
**Approach:** Raised the release cap from 200 to 500; debug builds are uncapped (and the char counter is hidden) so the owner can paste long notes while testing. Cap is a client constant in `FeedbackScreen.kt`.
**Reviewer notes:** Pre-existing commit reconstructed from its message. The CARDS-8 todo bullet was still in `docs/todo.md` after this shipped — I removed it this cycle (see the `docs(todo)` cleanup below).
