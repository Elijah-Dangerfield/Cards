-- V80: Drop the placeholder "50% OFF" sale framing on the Sunset felt (SHOP-2).
--
-- The Sunset felt carries a "50% OFF" badge plus weekend-sale wording in its
-- subtitle and description, but nothing is actually discounted; the half-price
-- claim was a demo-time placeholder from the V5 seed. Owner directive
-- (CARDS-4N): kill the fake discount everywhere it's seeded.
--
-- Append-only UPDATE over the existing row so dev Flyway checksums stay intact;
-- GET /v1/products reflects the change on next deploy after the catalog cache
-- rolls. Prod carries the same row and must end up identical; that mirror is a
-- reviewed, credentialed write and stays with the human (see developer-todo).

UPDATE products
SET badge_by_locale = NULL,
    subtitle_by_locale = '{"en":"Table felt","es":"Fieltro"}'::jsonb,
    description_by_locale = '{"en":"Warm sunset-orange felt for your playing surface. Visible to you only in solo games."}'::jsonb
WHERE id = 'felt_sunset_weekend';
