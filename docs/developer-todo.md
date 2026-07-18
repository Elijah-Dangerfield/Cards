# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---
## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [ ] **Set up the LLC before you take real money.** Prudent, not panic — just don't have paid purchases running into a personal account. The fast parts take a few days:
  1. **Form the LLC** (NY, online). The NYC newspaper-publication step has a 120-day window, so it doesn't block launch.
  2. **Get an EIN** — free and instant from the IRS once the LLC exists.
  3. **Open a business bank account** (Mercury/Novo, ~same day), and set your App Store + Play payouts to it.
  4. **Get a tech-E&O / liability insurance quote** — the real backstop, roughly $500–1,500/yr.
  5. **Then** turn on paid chip packs and launch.
  - Skip for now: converting the Apple/Google accounts to "Organization" (needs a D-U-N-S number, takes weeks). Launch on your individual accounts; convert later.
  - A 30-minute accountant/lawyer call confirms the setup and gets you the insurance quote.
- [ ] **Launch day: switch prod Apple purchases to Production.** When the app goes live on the App Store, run:
  `fly secrets set APPLE_STORE_ENVIRONMENT=Production -a cards-server-prod`
  After this, TestFlight purchases stop working (that's expected) — test future chip packs on a debug build against dev. Background: [decisions.md](./decisions.md) 2026-07-07.

---
