# In-flight log

## docs(wiki): progression + achievements pages match PROG-12 re-pull (ENG-32)

**Problem:** `docs/wiki/progression.md` and `achievements.md` still described pre-PROG-12 reward timing — server-minted chips becoming visible only on the next balance overwrite / trigger edge.
**Approach:** Rewrote the reward-visibility wording on both pages to the as-built contract: minting endpoints return `walletBalance` only when they actually minted, and the client reacts with an immediate `ChipsRepository.sync()` re-pull, never a direct apply. Both pages now link to wallet.md for the ordering argument rather than duplicating it.
**Reviewer notes:** Verified against `ProgressionRepositoryImpl.sync` (walletBalance branch ~224) and `AchievementRepositoryImpl.sync` (~194) rather than trusting the todo text. None surprising.

## docs(server): architecture + deploy docs match the live prod app (ENG-33)

**Problem:** `docs/wiki/architecture.md` still called the prod Fly app "future `cards-server`", omitted the `apps/admin` config UI, and `fly.toml` / `server-deploy.yml` / `DEPLOY.md` carried the same pre-prod "when we ship" story — including a DEPLOY.md footer that told you to create the prod app that already exists.
**Approach:** Named the real apps everywhere (`cards-server-dev` / `cards-server-prod`), described the approval-gated prod deploy, added the config-admin row + paragraph to architecture.md, and deleted the contradictory "When cards-server (prod) ships" DEPLOY.md footer in favour of its existing "Standing up prod" section. Also corrected DEPLOY.md's stale dev-VM facts (256MB / scale-to-zero → 512MB / one warm machine) since they contradict fly.toml on the same page. Alternative was to touch only the two lines the todo named, but leaving the rest of the stale prod story standing would keep the docs self-contradictory.
**Reviewer notes:** DEPLOY.md claimed "an equivalent prod sweep workflow lives separately" — it doesn't (only `sweep-anon.yml`, dev-only, exists). I made the doc honest about that rather than inventing the workflow.
**Deferred:**
- Anonymous-user sweep has no prod workflow (`sweep-anon.yml` targets dev only). Nothing filed yet — reviewer please triage whether this needs a todo before launch.
