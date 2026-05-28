## refactor(shake): route ShakeHandler through DispatcherProvider.main

**Problem:** `ShakeHandler` constructed its `CoroutineScope` with raw `Dispatchers.Main`, violating the repo's dispatcher-injection rule (production code consumes `DispatcherProvider.*` so tests can swap a `TestDispatcher`).
**Approach:** Added `dispatchers: DispatcherProvider` to the `@Inject` constructor and routed the scope through `dispatchers.main`. `apps:compose` already depends on `:libraries:flowroutines`.
**Reviewer notes:** No new test — the todo entry explicitly noted this dispatcher swap doesn't change observable behavior and no test sibling exists.

## test(server): pin /v1/avatars route contract

**Problem:** `avatarRoutes()` is the unauthenticated endpoint the avatar picker hits before the Supabase JWT lands, but it had no route test — the "anon ok / full registry / palette / Cache-Control / unlock id presence" contract was unverified despite sibling routes (Equipment, Wallet, Inventory, Me) all having one.
**Approach:** New `AvatarRoutesTest` using Ktor `testApplication` against the route in isolation (no auth plugin installed since the endpoint is intentionally anon). Five tests pin: 200 without auth, pack count + ordering matches `AvatarPacks.all`, `backgroundPalette == AvatarPalette.values`, `Cache-Control: public, max-age=60`, premium packs carry `unlockProductId` while starter is null.
**Reviewer notes:** None.

## fix(room): pin emote-tray trigger to a square footprint

**Problem:** The emote-tray trigger on the play-poker screen rendered as an ellipse instead of a perfect circle — `EmojiButton` uses `defaultMinSize` on its inner Box and emoji glyphs measure wider than tall, so the Surface grew horizontally even though `Radii.IconButton` (= `Radii.Round`, `percent=50`) wants a 1:1 aspect ratio to read as a circle.
**Approach:** Lifted the existing `triggerFootprint = iconSize + 2 × padding` computation out of the popup branch and applied `Modifier.size(triggerFootprint)` to the outer wrapping `Box`. Both the live `EmojiButton` and the `CooldownChip` Surface inherit the fixed square, and the popup's vertical offset still keys off the same value so the picker still anchors cleanly under the trigger.
**Reviewer notes:** Considered fixing this inside `EmojiButton` itself but that would touch every caller and the size-vs-glyph trade-off is documented as intentional on the primitive. Pinning at the call site is the smaller, targeted change.

## test(level): pin XP curve + LevelProgress derived props

**Problem:** `Level.kt` (quadratic XP curve, `levelProgressFor` resolver, `LevelProgress` derived fraction with `coerceIn(0f, 1f)` + divide-by-zero guard) is consumed by Home / Stats / Profile / Shop / Room VMs but had no test pin; the `MAX_LEVEL=100` clamp, the negative-XP clamp, and the fraction-fallback were all unverified.
**Approach:** Added `LevelTest` covering the curve at known levels, the `<1` clamp on `xpToLevelUpFrom`, the level-from-XP boundaries (0, 99, 100, negative, beyond MAX_LEVEL), the three derived `LevelProgress` properties, plus a monotonicity sweep. New `commonTest.dependencies` block added to `:libraries:cards` (it had none) wired to `:libraries:flowroutines:testing` for source-set parity with the rest of the module graph; the tests themselves use plain `kotlin.test` because `Level.kt` is pure math.
**Reviewer notes:** None.
