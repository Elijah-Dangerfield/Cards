# Cards — Product Docs

Source-of-truth product strategy documentation for Cards. These docs define **what we're building, why, and in what order**. They are the reference for product decisions; engineering decisions live elsewhere (see `docs/decisions.md` for cross-cutting technical decisions).

## Documents

- **[product-spec.md](./product-spec.md)** — The full product descriptor & mechanism reference. The 10-part spec covering brand, player journey, every system (XP, leagues, economy, shop, etc.), social mechanisms, lineage from comparable apps, phased roadmap, and brand checks. **Read this for any product-level question.** ~10,000 words; structured so you can jump to a section by anchor.

- **[v1-mvp.md](./v1-mvp.md)** — The V1 launch scope: must-have / should-have / could-have / explicit non-goals, success metrics, quality bar, rough sequencing, V1.x patch plan. **Read this for any "is X in V1?" question.** Companion to the spec.

## Conventions

- These are **living documents** — revise as decisions land. Each has a version footer with the last review date.
- Section references use `§` notation (e.g., `§4.7 weekly leagues`). The spec is the canonical address space.
- Open decisions are tracked in [product-spec.md Part 9](./product-spec.md#part-9--open-decisions-revisit-when-their-phase-comes-up) — flagged so they don't get re-litigated.
- Cross-cutting technical decisions (not product) live in [`docs/decisions.md`](../decisions.md).
- Brand checks before shipping any user-facing feature: [product-spec.md Part 10](./product-spec.md#part-10--brand-checks-use-this-list-when-shipping-anything).

## When to update

- **product-spec.md** — when a fundamental design changes (a system gets reworked, a phase moves, a new mechanism is added/removed). Update the version footer and the "next review trigger" date.
- **v1-mvp.md** — at end of each V1 phase milestone, and again after 4 weeks of post-launch telemetry.

## Initial version

Drafted 2026-05-15 → 2026-05-16. Origin: stepping back from "ship V1" to define **what Cards actually is** before building further. Locked decisions: identity (poker app), vibe (Card Hall — gamified leagues + seasons), retention loop (weekly competitive ladder), monetization (chip-pack IAP only, single-currency economy with unlock-only prestige tier). Several mechanics considered and rejected during the session — login streaks, two-currency economy, gameplay-aid purchases, expiring chip bonuses, synthetic users — are documented in [product-spec.md Appendix C](./product-spec.md#appendix-c--removed-mechanics).

**Docs in this folder:**
- [product-spec.md](./product-spec.md) — full product descriptor (positioning, systems, economy, multiplayer, integrity, roadmap, brand checks, lineage, open decisions, removed mechanics)
- [v1-mvp.md](./v1-mvp.md) — V1 launch scope (must-have / should-have / could-have / non-goals + success criteria + sequencing)
- [voice-and-copy.md](./voice-and-copy.md) — voice principles + per-surface copy library + localization principles
