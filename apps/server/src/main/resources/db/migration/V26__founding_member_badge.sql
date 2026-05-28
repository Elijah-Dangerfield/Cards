-- ─────────────────────────────────────────────────────────────────────────────
-- V26: Founding-member badge — seed the unlock-only cosmetic + add a
-- monotonic `seq` to profiles so the application layer can recognise
-- which users sit inside the founding window.
--
-- Per [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — Founding member badge for the first 1000 users]:
--   * The shape is a badge (fits the permanent seat-badge slot landed
--     in `feat(room): permanent seat-badge slot on the human player
--     area` — `EquippedFelt.kt:badgeEmojiForProductId` reads from
--     `badgeRegistry`, currently empty).
--   * Detection is server-side at profile creation — the application
--     layer compares each newly-inserted profile's `seq` against the
--     1000-user threshold and conditionally inserts both the inventory
--     row and a celebratory inbox UserMessage.
--   * Notification is the existing `kind = 'inbox'` UserMessage so the
--     user discovers the badge by visiting the Notifications tab
--     rather than a celebration on app entry.
--
-- Two pieces of schema work:
--
-- 1) `seq BIGSERIAL NOT NULL UNIQUE` on `profiles`. Postgres auto-
--    creates the backing sequence and fills existing rows during the
--    `ALTER TABLE ADD COLUMN`. Pre-launch the existing-row order
--    doesn't matter (no real users); per [docs/agent/worker-prompt.md
--    "Pre-launch posture"] there's nothing to backfill defensively.
--    The application layer reads back the inserted `seq` value and
--    grants the founding-member cosmetic when `seq <= threshold` —
--    the threshold lives in `StarterInventory.kt` so a future cohort
--    extension is a one-line change there, not a schema migration.
--
-- 2) `badge_founding_member_1000` catalog row. `unlock_only = TRUE`
--    keeps it out of the shop catalog
--    (`PostgresProductCatalogSource.read` filters `WHERE unlock_only
--    = FALSE`) but reachable via `readById` for the inventory grant.
--    `is_equippable = TRUE` so the user can toggle the badge from
--    My Items (badges live in `CosmeticSlot.Badge` which expects
--    user-equipped behavior, same shape as titles + card backs).
--    `cost_chips = 0` because the `chip_offer` kind constraint
--    requires a non-null cost; the unlock_only flag is what actually
--    keeps it off the shelf.
--    Sort order 760 sits below the existing earned titles (730-750)
--    so any future admin toggle to make it purchasable would land it
--    in the right shelf neighborhood.
--
-- Client wiring lands in the same commit:
--   * `badgeRegistry` in `libraries/ui/.../EquippedFelt.kt` gains
--     `"badge_founding_member_1000" → "🏛"` so the seat-badge slot
--     renders the emoji once the inventory row exists + the user
--     equips it.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE profiles
    ADD COLUMN seq BIGSERIAL NOT NULL;

ALTER TABLE profiles
    ADD CONSTRAINT profiles_seq_uq UNIQUE (seq);

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'badge_founding_member_1000', 'chip_offer', 760, '🏛', FALSE, NULL,
    '{"en":"Founding Member","es":"Miembro fundador"}'::jsonb,
    '{"en":"Badge · founding","es":"Insignia · fundador"}'::jsonb,
    '{"en":"Granted to the first 1,000 players to find a seat. Equip from your items — it shows at your seat at the table.","es":"Concedida a los primeros 1.000 jugadores. Equípala desde tus objetos — aparece en tu asiento de la mesa."}'::jsonb,
    'badge.founding_member_1000', 0, 1, TRUE, TRUE
);
