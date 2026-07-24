# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---
## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [x] **Set up the LLC before you take real money.** Done 2026-07-24 — LLC + business banking in place; App Store / Play payouts point to the business account.
- [ ] **Launch day: switch prod Apple purchases to Production.** When the app goes live on the App Store, run:
  `fly secrets set APPLE_STORE_ENVIRONMENT=Production -a cards-server-prod`
  After this, TestFlight purchases stop working (that's expected) — test future chip packs on a debug build against dev. Background: [decisions.md](./decisions.md) 2026-07-07.
