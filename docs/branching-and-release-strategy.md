# Branching and release strategy

This is a thinking document, not a rulebook. It walks through the
branching models that exist, why each one came to be, what each one is
actually solving, and what the tradeoffs look like for **this specific
repo** — a solo project shipping a mobile app through two stores with
release-please driving the version bumps.

It's long on purpose. The point is to build enough understanding that
the choice we've made (or any future change to it) isn't dogma.

## The problem each model is trying to solve

Every branching model is an answer to three questions:

1. **Where is "the current shippable code"?** One branch, many branches,
   a specific tag?
2. **Where do in-flight changes live while they're being reviewed or
   stabilized?** On main alongside everything else, on a side branch,
   on a long-lived integration branch?
3. **When a shipped version has a bug, where does the fix land?** On
   main and get cherry-picked? On a release branch that's still alive?

Different teams in different contexts answer these differently. The
"right" answer depends on how often you ship, how many people are in
the repo, how expensive a bad release is, and how much coordination
overhead you can tolerate.

## Model 1: Trunk-based development (TBD)

The shortest summary: **`main` is always shippable. Short-lived
branches merge into `main`. Releases are tags on `main`.**

```
main:        A──B──C──D──E──F     <- always green, always deploy-ready
                      │     │
                    v1.0  v1.1    <- tags are the release markers
```

- All work lands on `main` behind feature flags if it isn't finished.
- Branches off `main` live for hours to days, not weeks.
- Production releases are cut by tagging a specific commit.
- Hotfixes go on `main`, get tagged with a new patch version, ship.

**Who uses it:** Google, Facebook/Meta, Netflix, most large modern
product companies. Every CI/CD SaaS vendor's default assumption.

**Why they use it:**

- Fewer long-lived branches means fewer merge conflicts and less drift.
- Integration happens continuously, so you find problems fast.
- Fits well with fast-moving teams where "release" happens daily or
  hourly.

**What it asks of you:**

- Your `main` has to actually stay green. This is enforceable with
  branch protection + required CI, but it's a discipline.
- In-progress features need a way to hide from users until they're
  ready. Usually feature flags. Sometimes "just don't wire it into
  the UI yet."
- You need a version/changelog tool that derives releases from commit
  history, because you're not maintaining a manual release-notes file.
  This is why release-please and semantic-release exist.

**What it handles badly:**

- Post-release stabilization when `main` has already moved on. If you
  ship v1.0 from commit D and then a week later you find a v1.0 bug,
  but `main` is now at commit K with a bunch of unrelated new work —
  the fix on `main` can't necessarily be delivered to v1.0 users
  without rolling the new work too.
- Apple/Play store review cycles. You upload a build, Apple takes
  3–7 days to review. Meanwhile `main` moves forward. If Apple
  rejects, you need to ship a fix *for the version you submitted*,
  not for what `main` is now.

## Model 2: GitFlow (classic)

Published by Vincent Driessen in 2010. The shortest summary:
**multiple long-lived branches with specific roles, coordinated merges
between them.**

```
main:       ──────────────●─────────●─────   <- tagged releases only
                         /           \
release/1.1: ●──●──●────●       ●────●       <- stabilize here before ship
             /           \     /
develop:   ──●──●──●──●──●───●──●──●──●──    <- integration branch
               \     \         \
feature-a:      ●──●──●         \
feature-b:             ●──●──●───●
```

- `main` only ever has released commits (v1.0, v1.1, …).
- `develop` is the active integration branch where features land.
- `feature/*` branches off `develop` and merge back.
- `release/X.Y` branches off `develop` when you're preparing a release.
  Bug fixes during the release go on this branch. When it's ready, it
  merges into both `main` (with a tag) and back into `develop`.
- `hotfix/*` branches off `main` for urgent production fixes. Merge
  into both `main` and `develop`.

**Who used it:** A lot of 2010s enterprise shops, banks, shrinkwrap
software companies, anyone shipping a versioned desktop product on a
slow cadence. Less popular now.

**Why it was popular:**

- A release branch gives you a stable place to do QA + bug-fix +
  release-notes work without blocking people adding new features on
  `develop`.
- You always know exactly what's in production (`main`'s latest tag).
- It models the reality of "we ship every 6 weeks, stabilization takes
  2 weeks, features keep coming in parallel."

**Why it fell out of favor:**

- Six branches to reason about is a lot of cognitive overhead.
- Long-lived `develop` vs `main` creates constant merge conflicts.
- CI/CD tools default to "deploy from main" and GitFlow forces main to
  be a museum.
- Continuous deployment made "wait for the release branch" feel slow.

## Model 3: Release branches on top of trunk-based (the practical middle ground)

This is the one you asked about. It's **TBD as the default, with
short-lived release branches spun up only when you need stabilization
for a specific version.**

```
main:          A──B──C──D──E──F──G──H──I──   <- always moves forward
                     │           │
              release/1.0      release/1.1
                     │           │
                    1.0.0      1.1.0
                     │           │
                    1.0.1      1.1.1    <- hotfixes live on their branch
                   (fix)       (fix)
```

- `main` behaves like TBD: short-lived feature branches, continuous
  integration, always shippable.
- When you're about to cut v1.0, you create `release/1.0` off `main`.
- `release/1.0` gets:
  - The initial v1.0.0 tag
  - Any subsequent v1.0.x hotfix commits, which get tagged v1.0.1,
    v1.0.2, etc. on that branch
  - Optionally merged back to `main` after each hotfix so the fix isn't
    lost when v1.1 gets cut
- `main` keeps moving forward with v1.1 work.

**Who uses it:** Chromium. Android (AOSP). Rails. Ruby. Tons of
OSS projects with real users on old versions who need fixes without
being forced onto v-next.

**Why it's appealing for your case:**

- Apple rejects your v1.0.1 submission. You land the fix on
  `release/1.0`, retag v1.0.1 from the release branch HEAD, ship. Main
  can have already moved on to v1.1 work in parallel — it doesn't
  matter.
- No "line in the sand" redraw. The line lives on its own branch
  forever.
- Feels closer to how you'd think about it mentally: "the 1.0 line of
  code" is a thing you can point at.

**Why it's not automatic:**

- **release-please is opinionated toward main-only.** It can be
  configured to watch a branch other than `main`, but it doesn't
  naturally manage "here's main doing v1.1 AND a release/1.0 branch
  doing v1.0.x" as a dual track. You end up running two release-please
  configs, or you drop release-please on the hotfix branch and do
  manual tags + manual changelog entries.
- Merging release branches back to main creates conflicts if the same
  file changed on both sides (especially on `versions.properties` /
  `CHANGELOG.md` / `Config.xcconfig` — the files release-please
  rewrites).
- "Which branch should I PR against?" becomes a live question for
  every contributor. With solo-you that's cheap. With a team it adds
  coordination overhead.

## Model 4: GitHub Flow (TBD's simpler cousin)

One branch (`main`), short-lived feature branches, deploy from main
every merge. Popularized by GitHub the company, natural fit for SaaS.

Basically TBD, with even less ceremony — no formal "release", just
continuous deployment. Doesn't fit store-submitted mobile apps well
because "deploy" isn't just pushing a binary, it's Apple's review
queue.

## What companies actually do (and why)

The pattern varies by constraint:

- **SaaS, weekly/daily deploys, no store review gating** — TBD or
  GitHub Flow. Main is deployed. Versioning is often just "the
  timestamp" or "the build number." No real "release" event.
- **Mobile app, store review, staged rollout** — TBD with release
  branches for hotfixes, OR TBD with the discipline that rejections
  mean "bump the patch version and resubmit." The second is simpler;
  the first is what you reach for when you're big enough that v-next
  work can't pause waiting on the store queue.
- **Desktop software, enterprise contracts, LTS versions** — TBD with
  multiple long-lived release branches (release/1.x, release/2.x,
  release/3.x), each getting security fixes for years. Basically
  Chrome's model. Nobody would pick this voluntarily if they didn't
  have to.
- **Regulated industries (banking, medical)** — GitFlow-ish, because
  audit trails love "every release is a branch with a clear lineage."

**The pattern that matters:** companies rarely pick a model for
aesthetic reasons. They pick it because something broke and the new
model fixes that specific break. Release branches exist because
someone once needed to hotfix v3.2 while v4.0 was two-weeks-from-ship,
and they burned a week figuring out how to do that on trunk.

## What solo developers actually do

When it's one person:

- Most of the coordination overhead of GitFlow is wasted because
  nobody is waiting on anybody.
- The benefits of release branches (parallel tracks) are weaker
  because you're not *actually* working on v-next while stabilizing
  v1.0. You're one human, one focus.
- But solo developers ship to the same stores with the same review
  queues as companies. So the "Apple rejected 1.0.1, I need to
  resubmit" pain is identical.

Most solo mobile devs land in one of two camps:

1. **Pure TBD with "just bump the patch."** Rejection = 1.0.2. Works
   fine. Only downside is cosmetic — you end up with 1.0.2, 1.0.4 on
   your store page, looking like you ship more often than you do.
2. **TBD with ad-hoc retagging.** Which is what we just did. Delete
   the tag, retag at HEAD of main. Works, but every time Apple
   rejects, the ritual is: pull fixes into main, redraw the tag.
   That's the "line in the sand" you're feeling.

A release branch would cost you once to set up and would replace
redrawing the line with a merge. For one rejection per release,
marginal. For multiple rejections or for projects where v-next work
is happening while v1.0 is still in review, it pays off.

## What this repo actually is (as of today)

- Solo developer.
- One app shipped to two stores.
- release-please driving version bumps on merges to `main`.
- Apple review cycle can take 1–7 days.
- Play rollout is staged (10%) — once something ships to Play, backing
  it out is a real operation.
- The pain point: **Apple rejections force a re-ship for the same
  version, which means uploading a new binary under v1.0.1 after
  fixing whatever Apple flagged.** Today that's handled by
  deleting + retagging on main.

The re-ship cost in the current setup, per rejection, is:

1. `gh release delete v1.0.1 --cleanup-tag --yes`
2. `git tag v1.0.1 origin/main && git push origin v1.0.1`
3. Cancel the auto-triggered run, dispatch with `skip_play_store=true`.

Maybe 30 seconds of typing if you know the dance. The real cost isn't
the mechanics, it's remembering the dance and not forgetting a step.

## The case for a release branch here

- **One less line-in-the-sand feeling.** You stop "un-tagging" and
  start "merging fixes into release/1.0" which is a normal operation.
- **Main can move.** If you want to start working on 1.1 features
  while Apple is sitting on 1.0.1, you can, without the fixes for
  1.0.1 polluting the unreleased 1.1 changelog.
- **Clearer history.** `git log release/1.0` tells you exactly what
  ever shipped in the 1.0.x line. Today you'd have to follow tags.

## The case against a release branch here

- **release-please doesn't cleanly support it.** You'd either run two
  release-please configurations (one watching `main` for minor
  releases, one watching `release/1.0` for patch releases) or drop
  release-please on the hotfix branch and do tags by hand. The first
  has sharp edges around CHANGELOG generation; the second loses the
  thing release-please is good at.
- **You don't actually have v-next work in parallel.** When Apple
  rejects 1.0.1, you're not mid-feature on 1.1 — you're focused on
  fixing the rejection. The parallelism benefit is hypothetical.
- **Mobile store releases are already serialized.** Play rolls out
  one release at a time per track. You can't have 1.0.1 and 1.1.0
  both actively rolling out on the production track anyway. So
  maintaining a 1.0.x line doesn't give you much that a linear
  sequence (1.0.1, 1.0.2, then eventually 1.1.0) doesn't.
- **Complexity you'd be the only one paying.** Every branching
  model's cognitive overhead is paid by everyone who touches the
  repo. That's you. You pay for the machinery on every single
  rejection, not just when it pays off.

## What could actually help (without changing the model)

The pain in the current setup isn't really the branching model — it's
the ritual. A few things could reduce the ritual without moving to
release branches:

1. **A one-shot `retag-release` action** — a workflow_dispatch that
   takes a version string, deletes the existing tag + release, retags
   at main HEAD, and fires release.yml with `skip_play_store=true`.
   Same three commands, but one button in the Actions UI.
2. **Make `skip_play_store` respected on tag push** — right now you
   have to cancel + redispatch because the push trigger doesn't see
   workflow_dispatch inputs. A tag-name convention (e.g. `v1.0.1-ios`
   or `v1.0.1-resubmit`) or a repo variable could carry the signal
   through push triggers.
3. **A "what would release-please do" dry-run action** — lets you see
   what a release-please merge would cut (1.0.2? 1.1.0?) before you
   merge its PR. Would've caught the "oh wait, it's going to bump to
   1.0.2 when I wanted 1.0.1 resubmit" confusion earlier.

None of these change the branching model. They just smooth the
trunk-based retag pattern to be less of a dance.

## Recommendation for this repo

Stay on trunk-based. Here's the short justification:

- release-please is aligned with it.
- Solo dev + store-submitted app + occasional rejection = retag cost
  is real but manageable.
- Release branches would add real machinery (dual release-please
  configs, merge-back ritual) to solve a problem we hit maybe once or
  twice per version.
- The smoother-ritual options above (one-shot retag action, skip-play
  signal on tag push) are ~1 hour of work and cover 80% of the pain.

If you ever:

- Hire a second developer
- Start shipping multiple major versions that need long-term support
- Move to a cadence where v-next is actively underway while v-current
  is in Apple review

… revisit this doc. The release-branch model would start earning its
keep.

## Recommended reading

If you want the canonical sources:

- **Trunk-based development:** https://trunkbaseddevelopment.com/ —
  Paul Hammant's site. Opinionated, good summary of why the model won
  in most shops.
- **GitFlow original post:** https://nvie.com/posts/a-successful-git-branching-model/ —
  the 2010 article. Author has since added a disclaimer that it's
  outdated for most teams now.
- **Google's Monorepo paper:** *"Why Google Stores Billions of Lines
  of Code in a Single Repository"* (CACM, 2016) — the scale at which
  trunk-based stops being a preference and becomes a requirement.
- **Chromium's branching docs:** https://www.chromium.org/developers/branches —
  real-world example of TBD + release branches for an LTS-style
  product.
- **release-please docs on branch strategies:**
  https://github.com/googleapis/release-please — especially the
  "release types" and "manifest" sections, to understand where the
  tool has sharp edges.
