# Client patterns

Cross-cutting patterns used in the client. Each section is a pattern, where it lives, when to reach for it, and what the alternatives were.

## Session-aware repository refresh

Repositories that own server-driven reference data (shop catalog, achievements catalog, avatar packs, profile) refresh on **session boundaries**, not on screen entry or fixed-time TTLs.

### Shape

- A `SessionTracker` in `:libraries:cards` publishes `Session(id, startedAtMs, reason)` when the process cold-boots or the app foregrounds after **≥ 15 min** in background.
- Each adopting repo persists its snapshot via `:libraries:storage`'s `Cache<T>` along with `lastFetchSessionId` + `fetchedAtEpochMs`.
- On init, the repo hydrates from disk (first frame has content) and self-triggers a refresh when the published session id rolls past `lastFetchSessionId`.
- Pull-to-refresh still forces a fetch independently.
- Snapshots older than 7 days are dropped on init.
- Repos expose `observeIsRefreshing()` so the screen can show its own spinner without the VM knowing which call triggered the refresh.

### When to reach for this

If your repo owns server-driven reference data the user expects to be "reasonably fresh but not constantly re-fetched," this is the pattern. The shop catalog was the first adopter. Adopt for inventory, avatar catalog, achievements, profile when each gets its next touch (each needs a per-endpoint call before adoption — see `developer-todo.md`).

### Alternatives rejected

- **Bump in-memory freshness window to 24 h.** Doesn't solve the cold-start-empty problem; the VM-lifetime trick is load-bearing forever.
- **Refresh on every tab entry.** Wastes bandwidth.
- **A `SessionAwareCache<T>` superclass.** Premature abstraction — each repo's "what's too stale to show" + "what triggers a refresh" questions differ enough that a forced base class would mostly export hooks. Revisit once a third repo adopts the pattern.

### Key files

- `SessionTracker` (`:libraries:cards`).
- `ProductsRepositoryImpl` (`:libraries:products:impl`) — the first adopter, the reference example.

---

## App-scoped buses for cross-tab one-shot signals

When one part of the app wants to signal another part across a tab boundary — and the target doesn't have a sub-route to ride on — use a conflated, consume-once `Channel` at app scope.

### Shape (the canonical example: shop deep-link)

The Edit Profile screen's "Get more avatar packs" link wants to land the user on the avatars shelf of the Shop tab. The Shop tab root (`ShopRoute`) is arg-less by routing rules (tab-root args get clobbered by `restoreState`), so we can't carry the target category as a route field.

The solution:

- A `ShopDeepLinkBus` (`:features:shop` api) — a conflated, consume-once `Channel<ShopCategory>` at app scope.
- The initiator (Edit Profile) calls `bus.requestScrollTo(ShopCategory.Avatars)`.
- The Shop VM observes the bus on init and mirrors the request into `ShopState.pendingScrollCategory`.
- The grid measures each section header's content offset (`onGloballyPositioned` → `positionInParent`) and scrolls to the target once measured, then fires `ScrollConsumed` to clear the pending state.

The two properties this gets you:

- **Conflated** — the latest request wins (an old unread one doesn't replay later).
- **Consume-once** — a stale visit to the Shop later doesn't re-fire the scroll.

A cold deep-link works because the conflation holds the request until the Shop VM subscribes; the Shop VM is lazily constructed on first shop entry, so direct VM-pokes from the initiator wouldn't work.

### When to reach for this

- The initiator and the target live in different tab graphs.
- The target's route is arg-less (tab root) or the signal is transient (scroll, focus, expand-a-section) rather than navigable.
- The target's VM might not exist when the signal fires.

### Alternatives rejected

- **Route arg on the tab root** — silently dropped by `restoreState`.
- **Resolve the target VM and poke it directly** — the VM may not exist yet; couples initiator to target lifecycle.
- **A dedicated sub-route as the scroll anchor** — heavier than a one-shot signal for what is transient state, not a destination.

### Sibling buses

Same "app-scoped one-way signal" idea, different delivery semantics — these are **not** conflated / consume-once:

- `SessionRejectionBus` (`:libraries:networking`) — signals the auth layer that a token refresh was definitively server-rejected. The impl is a non-replaying `MutableSharedFlow` (buffer 8, `DROP_OLDEST`): a rejection fired before the collector subscribes is *not* held, which is fine because `SupabaseAuthRepositoryImpl` collects on `appScope` from its `init {}` — constructed during auth bootstrap, before any authed call can fail. The bus also exposes `rejectionEpoch`, a synchronous monotonic counter `authedCall` compares before/after a failed request to classify a 401 as session-death vs a transient hiccup.
- `AccessDeniedBus` (`:libraries:networking`) — the mechanically-parallel 403-with-envelope path (banned/suspended). Same non-replaying delivery; the app layer routes to the blocking access-denied screen.

The shop bus needs conflation because its consumer (the shop VM) is lazily constructed after the signal fires; the networking buses don't, because their consumers outlive every producer.

### Key files

- `ShopDeepLinkBus` (`:features:shop` api; impl in `:features:shop:impl`).
- `SessionRejectionBus`, `AccessDeniedBus` (`:libraries:networking`; impls in `:libraries:networking:impl`).
- `ShopViewModel` (`init {}` collects `deepLinkBus.scrollRequests`), `ShopState.pendingScrollCategory`.
