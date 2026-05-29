# In-flight — cycle 2026-05-29

## refactor(server): retire game_events write path

**Problem:** §B0 P2 — the `game_events` durable log (V31, shipped 2026-05-28) was built for the event-sourced direction; the 2026-05-29 architecture flip to snapshot-only state means nothing reads from it, and the writer + DI binding + Postgres table are dead weight.

**Approach:** Pulled the producer end-to-end. Deleted `PostgresGameEventWriter` + the `GameEventWriter` interface (and its `NoOp`), stripped the `eventWriter` constructor param from `GameSession` + `InMemoryGameSessionRegistry`, removed the persistence try/catch that turned writer failures into `IntentResult.Rejected("persistence failed: …")`, dropped `GameEventsTable` from `Tables.kt`, deleted `PostgresGameEventWriterTest` / `TestGameEventWriters`, trimmed the writer-shaped tests from `GameSessionTest` + `GameSessionRegistryIntegrationTest`, fixed the one `RoomSocketRoutesTest` constructor site. New Flyway `V47__drop_game_events.sql` drops the index + table.

Kept the `GameEventEnvelope` carrier in `:libraries:gameplay` — per the todo, "may stay if it's still useful as an in-memory shape." It's small, self-contained, and the parked B5 rolling-tail option would re-use it. Kept the reusable `Table.jsonb(name)` helper in `JsonbColumn.kt` for the same reason: the B0 snapshot table will need exactly that primitive.

**Reviewer notes:** Worth a sanity check that you actually want `GameEventEnvelope` and `JsonbColumn` to survive — the prompt explicitly leaves both as judgement calls. If you want them gone, the deletes are mechanical. The `id: UUID` on `GameSession` also outlives its original justification ("future replay path"); rewrote the kdoc to reference the upcoming snapshot table rather than removing the field, since the snapshot writer needs exactly this key.

**Deferred:** Updated the §B0 P1 todo entry to point at `Table.jsonb(name)` as the snapshot column primitive and to stop referencing the now-deleted `PostgresGameEventWriter` as a template.
