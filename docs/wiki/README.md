# Cards Wiki

High-level, plain-language explainers of how Cards' systems actually work —
the bridge between the product "why" ([product-spec.md](../product/product-spec.md))
and the code "exactly how." Read these to get oriented before diving into a
subsystem; they're meant to stay readable without opening Kotlin.

If a page here ever disagrees with the code, the code wins — open an issue or
fix the page.

## Pages

- [State authority & sync](./state-authority-and-sync.md) — where a piece of state
  lives and who's authoritative: client-local vs. optimistic-local-then-reconciled
  vs. server-granted, and how to choose given Cards is offline-first.
- [Chip grants](./chip-grants.md) — where chips come from (starter, welcome-week,
  bust protection), who's authoritative, and how the starter-grant reveal works.
