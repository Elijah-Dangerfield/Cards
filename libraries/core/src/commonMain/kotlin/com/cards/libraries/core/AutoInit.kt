package com.dangerfield.cards.libraries.core

/**
 * Marker for singletons that need to be constructed at **app boot**
 * rather than lazily on first injection.
 *
 * ## Why
 *
 * Kotlin-inject singletons are constructed on first resolve. For
 * repositories that wake up on cold boot (hydrate from disk, kick off a
 * background refresh, subscribe to [com.dangerfield.cards.libraries.cards.AppEventBus]),
 * that means *nothing happens* until someone first injects them — often
 * not until the user navigates to the relevant screen. Tabs end up cold
 * on first open, network calls fire late, and `init {}` side effects
 * (e.g. registering as an [com.dangerfield.cards.libraries.cards.AppEventListener])
 * never run until something else triggers it.
 *
 * Implementing [AutoInit] hands the singleton to the boot-time iterator
 * in `App.kt`, which resolves the full `Set<AutoInit>` once on first
 * composition. The mere act of resolving the set forces every
 * contributor to construct, so their `init {}` blocks run.
 *
 * ## How
 *
 * Pair an `AutoInit` boundType with whatever the class's primary
 * public binding is:
 *
 * ```kotlin
 * @SingleIn(AppScope::class)
 * @ContributesBinding(AppScope::class, boundType = ProductsRepository::class)
 * @ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
 * @Inject
 * class ProductsRepositoryImpl(...) : ProductsRepository, AutoInit
 * ```
 *
 * The class still resolves normally when someone injects
 * `ProductsRepository`; the second binding just adds it to the
 * boot-warm set so it ALSO constructs at app start.
 *
 * ## When to use it
 *
 * **Yes:**
 *  - Disk-backed repositories whose `init {}` hydrates from disk so the
 *    first-frame render of any consuming screen is hot, not cold.
 *  - Listeners registered in `init {}` for app-lifecycle events
 *    ([com.dangerfield.cards.libraries.cards.AppEventListener] etc.) —
 *    they have to exist before their first event fires.
 *  - Anything whose first-touch latency the user actually notices
 *    (catalog grids, avatar pickers, profile state).
 *
 * **No:**
 *  - Low-frequency / debug-only singletons (`ConfigOverrideRepository`,
 *    QA menus). Lazy is correct; we don't want to do disk reads at boot
 *    for surfaces nobody opens.
 *  - ViewModels. Construct them when the screen mounts, not at boot.
 *  - Things whose `init {}` is empty / cheap. There's no benefit
 *    forcing construction if nothing meaningful happens at construction.
 *
 * ## Failure mode
 *
 * Forgetting the marker is a *performance* regression, not a
 * correctness one — the class still works, just lazily. So drift on
 * this is low-risk; the bigger risk is the opposite (overuse making
 * boot slow). Be picky.
 */
interface AutoInit
