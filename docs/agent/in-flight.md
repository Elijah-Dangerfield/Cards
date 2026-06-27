# In-flight

## fix(shop): drop the placeholder "50% off" sale on the Sunset felt (SHOP-2)

**Problem:** The Sunset felt carried a "50% OFF" badge plus weekend-sale wording in its subtitle and description, but nothing was actually discounted — a demo placeholder. Owner directive (CARDS-4N): kill the fake discount.
**Approach:** Append-only migration `V80__remove_sunset_felt_fake_sale.sql` nulls `badge_by_locale` and rewrites the subtitle ("Table felt") and description (drops the sale/half-price framing) for `felt_sunset_weekend`. Chose an append-only UPDATE over editing the V5 seed so dev Flyway checksums stay intact, matching the V64/V78 precedent. Added a `PostgresProductCatalogSourceTest` regression pinning that the sunset felt has no badge and no "sale"/"half price" wording, so the placeholder can't creep back.
**Reviewer notes:** Prod carries the same row and must end up identical; that mirror is a reviewed, credentialed write that stays with the human (same pattern as V78/ENG-3). Worth a developer-todo / Heads-up line so the prod catalog gets the same UPDATE.
