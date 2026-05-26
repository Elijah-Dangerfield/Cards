-- ─────────────────────────────────────────────────────────────────────────────
-- V21: Replace `emoji_pack_starter` with `avatars_starter` in the starter kit.
--
-- V18 added `emoji_pack_starter` to every user's starter inventory and the
-- client (`EmojiPackCatalog`) treated it as a free emote-blast pack —
-- granting 👋 👍 🎉 😀 in the in-game tray to every user from day one. That
-- conflated two product surfaces: avatar emoji packs (the profile picker,
-- starter pack always available) and emote blast packs (in-game tray,
-- purchase-only per product spec). Net effect: every user got four free
-- emote blasts at the table, eroding the paid-blast surface.
--
-- The intended starter kit is the avatar emoji pack the user already picks
-- from during onboarding. Access to those emojis is independent of the
-- inventory row (`AvatarPacks.Starter` is always available regardless of
-- owned IDs); this product row exists purely so My Items reflects ownership
-- of the pack the user started with.
--
-- Pre-launch: dropping rows directly rather than preserving them. No real-
-- user data to migrate carefully.
-- ─────────────────────────────────────────────────────────────────────────────

DELETE FROM inventory WHERE product_id = 'emoji_pack_starter';
DELETE FROM products WHERE id = 'emoji_pack_starter';

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'avatars_starter', 'chip_offer', 395, '🦊', FALSE, NULL,
    '{"en":"Starter Avatars","es":"Avatares iniciales"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"The starter pack of avatar emojis (🦊 🐱 🐼 🐯 🐸 🦁 🃏 🎲). Pick the one you wear at the table from Edit Profile."}'::jsonb,
    'avatars.starter', 0, 1, FALSE, TRUE
);

INSERT INTO inventory (user_id, product_id, cost_chips_at_purchase, purchased_at, acquisition_source)
SELECT p.user_id, 'avatars_starter', 0, p.created_at, 'earned'
FROM profiles p
ON CONFLICT (user_id, product_id) DO NOTHING;
