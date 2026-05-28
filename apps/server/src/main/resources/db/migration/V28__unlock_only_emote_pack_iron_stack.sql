-- ─────────────────────────────────────────────────────────────────────────────
-- V28: Seed `emotes_iron_stack` — third unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks beyond the seeded Eliminator + Baller
-- packs] sweep. V25 paired `BUST_DEALT_5` → `emotes_eliminator`, V27 paired
-- `TRIPLE_UP` → `emotes_baller`; this row extends to the survival-themed
-- pairing the todo explicitly suggested ("`NO_BUST_100` ('Survivor' pack)").
--
-- Pairing rationale: `AchievementId.NO_BUST_100` ("Iron stack", EPIC, bot-
-- mode-valid) currently grants 500 XP + 500 chips but no cosmetic. The
-- narrative beat (100-hand no-bust streak — sustained discipline, weathered
-- every bad-beat without going broke) maps cleanly to a defensive /
-- unbroken emote set for the seat that just refused to crumble.
--
-- Emoji set is `🛡️ 🧱 🗿 🦾` — none overlap with the four purchasable
-- packs (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal
-- 👑🃏♠️♥️), the V25 eliminator pack (🪦⚰️👻🥀), or the V27 baller pack
-- (💸💎🤑📈), so the available blast pool grows by four real options when
-- the pack lands in inventory. 🛡️ doubles as the pack icon emoji (mirrors
-- the achievement's icon, so a user landing in the unlock callout pattern-
-- matches "I just earned Iron stack" with "I just unlocked the Iron Stack
-- pack"). Picked 🦾 (mechanical arm) over 💪 (flexed bicep) because the
-- achievement name is literally "Iron stack" — 🦾 carries the hardened-
-- durable connotation, 💪 reads as generic strength and overlaps with
-- fierce's 💀 / 🔥 energy. Picked 🗿 (moai) over 🪨 (rock) because the
-- stoic-face reading sells "I am unmoved by your three-bet" better than a
-- pure inanimate object.
--
-- Follows the V25 / V27 / V20 unlock-only-cosmetic pattern:
--   - `unlock_only = TRUE` keeps it out of the shop catalog
--     (`PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE`)
--     but reachable via `readById` for the achievement grant.
--   - `is_equippable = FALSE` matches every other emote pack — packs
--     expand the blast pool, no per-emote equip toggle.
--   - `cost_chips = 0` because the chip_offer kind constraint requires a
--     non-null cost; `unlock_only` is what actually keeps it off the shelf.
--   - `unlock_level = 1` mirrors the other unlock-only cosmetics — gating
--     is via the achievement, not a level threshold.
--
-- Sort order 360 sits directly after the V27 baller pack (350) and V25
-- eliminator pack (340), so the unlock-only emote packs are visually
-- grouped if the shelf ever surfaces them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_iron_stack" → 🛡️🧱🗿🦾`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"NO_BUST_100" → "emotes_iron_stack"` so the client grant POST resolves
--     to this row.
--   * `cosmeticRewardFor(AchievementId.NO_BUST_100)` mirrors the mapping
--     for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_iron_stack', 'chip_offer', 360, '🛡️', FALSE, NULL,
    '{"en":"Iron Stack Emote Pack","es":"Paquete pila de hierro"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by surviving 100 hands in a row without busting. Unlocks 🛡️ 🧱 🗿 🦾 — for the seat that refused to crumble. Send from the in-game emote tray.","es":"Desbloqueado al sobrevivir 100 manos seguidas sin perder toda tu pila. Desbloquea 🛡️ 🧱 🗿 🦾 — para el asiento que se negó a derrumbarse. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.iron_stack', 0, 1, FALSE, TRUE
);
