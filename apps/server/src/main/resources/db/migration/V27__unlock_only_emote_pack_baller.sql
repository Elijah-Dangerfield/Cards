-- ─────────────────────────────────────────────────────────────────────────────
-- V27: Seed `emotes_baller` — second unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks beyond the seeded Eliminator pack] sweep.
-- V25 proved the achievement → emote-pack pathway with `emotes_eliminator`
-- (`BUST_DEALT_5`). This row extends the catalog with a second thematic
-- pairing so the unlock-only emote shelf doesn't read as "we built it for
-- one thing then walked away."
--
-- Pairing rationale: `AchievementId.TRIPLE_UP` ("Tripled up", EPIC, bot-mode-
-- valid) has no cosmetic reward today — currently grants 500 XP + 250 chips
-- only. The narrative beat (finished a hand with 3× your starting stack)
-- maps cleanly to a "showing off the stack" emote pack so the player who
-- just printed money has reactions matching the moment.
--
-- Emoji set is `💸 💎 🤑 📈` — none overlap with the four purchasable
-- emote packs (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal
-- 👑🃏♠️♥️) or the V25 eliminator pack (🪦⚰️👻🥀) so the available-blast
-- pool grows by four real options when the pack lands in inventory. Picked
-- 📈 (chart up) over 🚀 ("to the moon" is too crypto-coded for poker
-- semantics) and 💎 over 💰 ("diamond hands" reads as a poker-suit-adjacent
-- flex; 💰 reads as a generic moneybag and competes with 🤑's role in the
-- set).
--
-- Follows the V25 / V20 unlock-only-cosmetic pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = FALSE` matches the other emote packs (V12 left emote
--     packs equippable=false; the pack just expands the in-game blast pool,
--     no per-emote equip toggle).
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only cosmetics — gating
--     is via the achievement, not a level threshold.
--
-- Sort order 350 sits directly after the V25 eliminator pack (340) so the
-- unlock-only emote packs are visually grouped if the shelf ever surfaces
-- them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_baller" → 💸💎🤑📈`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"TRIPLE_UP" → "emotes_baller"` so the client grant POST resolves
--     to this row.
--   * `cosmeticRewardFor(AchievementId.TRIPLE_UP)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_baller', 'chip_offer', 350, '💸', FALSE, NULL,
    '{"en":"Baller Emote Pack","es":"Paquete acaudalado"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by tripling your starting stack in a single hand. Unlocks 💸 💎 🤑 📈 — for the seat that just printed money. Send from the in-game emote tray.","es":"Desbloqueado al triplicar tu pila inicial en una sola mano. Desbloquea 💸 💎 🤑 📈 — para el asiento que acaba de imprimir dinero. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.baller', 0, 1, FALSE, TRUE
);
