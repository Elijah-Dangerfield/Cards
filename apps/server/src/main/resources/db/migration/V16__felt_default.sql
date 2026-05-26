-- ─────────────────────────────────────────────────────────────────────────────
-- V16: Seed the `felt_default` catalog row + flag it as unlock-only.
--
-- Adds the in-app "Default" felt as a real catalog product so new users
-- have something visible in My Items from day one (granted server-side
-- on profile creation — see PostgresProfileRepository.findOrCreate +
-- meRoutes). Charcoal stays the purchasable "moody black felt" — the
-- default is distinct: $0, granted automatically, never shoppable.
--
-- `unlock_only = TRUE` keeps the row out of the shop catalog
-- (PostgresProductCatalogSource filters with `WHERE unlock_only = FALSE`).
-- `cost_chips = 0` because the constraint requires a non-null cost for
-- the chip_offer kind; the unlock_only flag is what actually keeps it
-- off the shelf.
--
-- Client mapping: `feltForProductId` already returns `EquippedFelt.Default`
-- for `felt_default` via the else branch (no client change required), and
-- the Default branch in `feltSurfaceColor` paints the app background — so
-- equipping does nothing visible, matching expectations for the "unstyled"
-- felt. The point is the user *owns it* and sees it listed under My Items.
--
-- Sort order 190 places it just above the other felts (200–220) so if a
-- future admin makes it shoppable (changes unlock_only to FALSE) the row
-- groups with its siblings.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'felt_default', 'chip_offer', 190, '🎴', FALSE, NULL,
    '{"en":"Default Felt","es":"Fieltro predeterminado"}'::jsonb,
    '{"en":"Table felt","es":"Fieltro de mesa"}'::jsonb,
    '{"en":"The classic table you start with. Equip from your items — visible to you only in solo games."}'::jsonb,
    'felt.default', 0, 1, TRUE, TRUE
);
