# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---
## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [ ] **Liability / insurance posture.** Decide what protection you want before taking real money — e.g. forming an LLC to cap personal liability, and/or business/tech-E&O insurance — for the scenario where a paying user's account or purchases are lost (e.g. an erroneous deletion) and they pursue it. The "never delete accounts with purchases" rule above is the first line of defense; this is the fallback. Talk to an accountant/lawyer; not something to DIY blind.
- [ ] **Launch day: flip prod Apple receipt validation from Sandbox to Production.** Pre-launch, `cards-server-prod` accepts **Sandbox** receipts so TestFlight testers exercise real purchases (set 2026-07-07). The moment the app is live on the App Store, run `fly secrets set APPLE_STORE_ENVIRONMENT=Production -a cards-server-prod`. After the flip, TestFlight purchases are rejected **by design** — test future chip packs on a debug build against dev instead. See [decisions.md](./decisions.md) 2026-07-07 for the rationale and the revisit trigger (a guarded dual-environment validator, only if post-launch TestFlight purchasing is ever missed).

---
