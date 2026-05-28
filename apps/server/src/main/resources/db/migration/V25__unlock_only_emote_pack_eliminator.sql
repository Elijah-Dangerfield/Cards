-- ─────────────────────────────────────────────────────────────────────────────
-- V25: Seed `emotes_eliminator` — the first unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — Emote / blast-pack unlocks] sweep. The unlock-only catalog already covers
-- titles (V17 / V22) and a card back (V20); this row proves the same pathway
-- works for emote packs, which were the last cosmetic shape lacking an
-- earnable example.
--
-- Pairing rationale: `AchievementId.BUST_DEALT_5` ("Eliminator", RARE,
-- bot-only) currently has no cosmetic reward and is the right thematic
-- match for a kill-themed emote pack — the player who has busted five
-- bots earns reactions fitting the moment. Sticking with a bot-mode
-- achievement also keeps the grant on the existing client-grant pathway
-- (the MP `BUST_DEALT_5_MP` sibling stays server-witnessed).
--
-- Emoji set is `🪦 ⚰️ 👻 🥀` — none overlap with the four purchasable
-- emote packs (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal
-- 👑🃏♠️♥️) so the available-blast list grows by four real options when
-- the pack lands in inventory. Picked these over `☠️` (too close to
-- fierce's 💀) and `🏴‍☠️` (compound emoji renders inconsistently).
--
-- Follows the V20 unlock-only-cosmetic pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = FALSE` matches the four purchasable emote packs
--     (V12 left emote packs equippable=false; the pack just expands the
--     in-game blast pool, no per-emote equip toggle).
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only cosmetics — gating
--     is via the achievement, not a level threshold.
--
-- Sort order 340 sits directly below the existing emote packs (300–330)
-- so any future admin toggle to make this purchasable would land it in
-- the right shelf neighborhood.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_eliminator" → 🪦⚰️👻🥀`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"BUST_DEALT_5" → "emotes_eliminator"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.BUST_DEALT_5)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_eliminator', 'chip_offer', 340, '🪦', FALSE, NULL,
    '{"en":"Eliminator Emote Pack","es":"Paquete eliminador"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by busting five opponents. Unlocks 🪦 ⚰️ 👻 🥀 — fitting last rites for the seat that just folded. Send from the in-game emote tray.","es":"Desbloqueado al eliminar a cinco oponentes. Desbloquea 🪦 ⚰️ 👻 🥀 — últimos ritos para el asiento que se acaba de retirar. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.eliminator', 0, 1, FALSE, TRUE
);
