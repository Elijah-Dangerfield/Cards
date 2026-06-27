# In-flight

## fix(shop): drop the placeholder "50% off" sale on the Sunset felt (SHOP-2)

**Problem:** The Sunset felt carried a "50% OFF" badge plus weekend-sale wording in its subtitle and description, but nothing was actually discounted — a demo placeholder. Owner directive (CARDS-4N): kill the fake discount.
**Approach:** Append-only migration `V80__remove_sunset_felt_fake_sale.sql` nulls `badge_by_locale` and rewrites the subtitle ("Table felt") and description (drops the sale/half-price framing) for `felt_sunset_weekend`. Chose an append-only UPDATE over editing the V5 seed so dev Flyway checksums stay intact, matching the V64/V78 precedent. Added a `PostgresProductCatalogSourceTest` regression pinning that the sunset felt has no badge and no "sale"/"half price" wording, so the placeholder can't creep back.
**Reviewer notes:** Prod carries the same row and must end up identical; that mirror is a reviewed, credentialed write that stays with the human (same pattern as V78/ENG-3). Worth a developer-todo / Heads-up line so the prod catalog gets the same UPDATE.

## fix(rooms): drop the fake "214 online now" count on Find a table (ROOM-10)

**Problem:** The public Find-a-table header showed a hardcoded "214 online now" subtitle with no real data behind it — fabricated social proof the honest-by-design matchmaking is meant to avoid. Owner directive: kill it.
**Approach:** Removed the subtitle outright rather than substituting honest copy — there's no real concurrent-players signal to show, and the explainer card already carries the reassurance. Dropped the `public_find_subtitle` string and its only usage in `PublicFindScreen`'s `RoomHeader`. If a real online count ever exists it's a separate item.
**Reviewer notes:** None.

## fix(rooms): calmer matchmaking search copy, fewer "real" mentions (ROOM-8)

**Problem:** The rotating reassurance while searching leaned on "real" in nearly every line, reading as protesting-too-much; it also cycled fast (5s) over only four lines.
**Approach:** Rewrote the rotation to six calm variants with at most one understated "real" mention ("Every seat here is a real person, no bots in the mix.") and bumped `ROTATE_INTERVAL_MS` 5s -> 8s so each line holds longer. Kept the honest framing without repeating the word. Chose to expand to six lines over three so the cycle doesn't feel repetitive at the slower cadence.
**Reviewer notes:** Copy is a judgement call — owner may want to tune individual lines.
