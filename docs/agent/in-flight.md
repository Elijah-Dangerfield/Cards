# In-flight (worker handoff log)

Each block below is one pushed commit this cycle. The reviewer reads these when writing the PR, then deletes this file.

## fix(server): lower card back + felt prices

**Problem:** Card backs (4,000-15,000) and felts (1,500-4,000) were premium-priced, but they're self-only cosmetics nobody else sees — too steep for the value.
**Approach:** Append-only migration `V63__lower_cosmetic_prices.sql` drops card backs to ~500-1,500 and felts to ~250-750, preserving the relative tiering (marble cheapest, holographic/diamond top; royal_red/midnight_blue/pine_green cheapest felts, sunset top). Targets matched the todo's rough guidance. Updated the one test that pinned a hardcoded price (`PostgresProductCatalogSourceTest` felt_royal_red 1500 → 250).
**Reviewer notes:** Prices are a judgement call within the todo's stated range; I kept ~8× compression so tiering stays legible. Verified via the testcontainer-backed `PostgresProductCatalogSourceTest` (Flyway applies V63, 18 tests pass). Shop catalog reflects on next deploy after the 5-min cache rolls.
