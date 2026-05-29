# In-flight — cycle 2026-05-29

## refactor(server): retire game_events write path

**Problem:** §B0 P2 — the `game_events` durable log (V31, shipped 2026-05-28) was built for the event-sourced direction; the 2026-05-29 architecture flip to snapshot-only state means nothing reads from it, and the writer + DI binding + Postgres table are dead weight.

**Approach:** Pulled the producer end-to-end. Deleted `PostgresGameEventWriter` + the `GameEventWriter` interface (and its `NoOp`), stripped the `eventWriter` constructor param from `GameSession` + `InMemoryGameSessionRegistry`, removed the persistence try/catch that turned writer failures into `IntentResult.Rejected("persistence failed: …")`, dropped `GameEventsTable` from `Tables.kt`, deleted `PostgresGameEventWriterTest` / `TestGameEventWriters`, trimmed the writer-shaped tests from `GameSessionTest` + `GameSessionRegistryIntegrationTest`, fixed the one `RoomSocketRoutesTest` constructor site. New Flyway `V47__drop_game_events.sql` drops the index + table.

Kept the `GameEventEnvelope` carrier in `:libraries:gameplay` — per the todo, "may stay if it's still useful as an in-memory shape." It's small, self-contained, and the parked B5 rolling-tail option would re-use it. Kept the reusable `Table.jsonb(name)` helper in `JsonbColumn.kt` for the same reason: the B0 snapshot table will need exactly that primitive.

**Reviewer notes:** Worth a sanity check that you actually want `GameEventEnvelope` and `JsonbColumn` to survive — the prompt explicitly leaves both as judgement calls. If you want them gone, the deletes are mechanical. The `id: UUID` on `GameSession` also outlives its original justification ("future replay path"); rewrote the kdoc to reference the upcoming snapshot table rather than removing the field, since the snapshot writer needs exactly this key.

**Deferred:** Updated the §B0 P1 todo entry to point at `Table.jsonb(name)` as the snapshot column primitive and to stop referencing the now-deleted `PostgresGameEventWriter` as a template.

## feat(cards): add MP-only achievement variants for parity

**Problem:** §A P1 — mode split was 16 BOTS-eligible / 8 EITHER / 2 MULTIPLAYER, underweight for an MP-centric brand. The todo asks for MP siblings of the harder bot achievements; counters stay server-driven (Phase 4.2 wires them), but the catalog + server-gate slice ships today.

**Approach:** Picked five MP variants spanning the rarity bands: `HANDS_100_MP`, `WIN_BY_FOLD_10_MP`, `DOUBLE_UP_MP` (all RARE), `TRIPLE_UP_MP` and `POT_5000_MP` (EPIC, with chip rewards). Each gets a new MP-suffixed custom-counter key alongside the existing `BUSTS_DEALT_MP`. Skipped the todo's two net-new shapes ("Beat a human heads-up ×5", "Showdown win against ≥2 humans") in favour of duping the canonical hard bot achievements — same criterion shape, same engine surface area, less to validate downstream. Mode-gating already exists in `AchievementRepositoryImpl` (the existing test covers it for `FIRST_BUST_DEALT_MP`), so the new entries inherit it for free. Server `ClientGrantableAchievements.Default.serverWitnessed` gains the five ids so a client POST to `/v1/me/grants/achievement/{id}` returns 403, not a self-grant. Tests: extended the GrantsRoutes parity check to assert every MP id in the policy returns 403; added two parametrised tests on `AchievementRepositoryImpl` exercising "fires in MP mode with counter met" + "does not fire in BOTS mode even with counter met" across all five new variants.

**Reviewer notes:** Names ("Regular at the table", "Pressure cooker", "Doubled up the hard way", "Tripled up the hard way", "Whale among whales") and chip rewards (500 for `TRIPLE_UP_MP`, 1_000 for `POT_5000_MP`) are my calls — louder than the bot originals to honour the "stronger status signal" framing. Easy to course-correct in a follow-up commit if the voice / economy feels off. No cosmetic rewards (no entry in `EarnableCosmetics.kt`) — the existing MP-only entries (`FIRST_BUST_DEALT_MP` / `BUST_DEALT_5_MP`) also grant XP/chips only, so I matched that pattern; the todo's earn-or-buy cosmetic catalog work is a separate item.

**Deferred:** Earnable-cosmetics tier-tagging (separate todo bullet) — kept as a follow-up since the RNG vanity-title retire/raise judgment-call belongs to the human/reviewer (the four single-showdown titles shipped two days ago).
