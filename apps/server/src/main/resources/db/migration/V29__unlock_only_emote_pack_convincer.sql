-- ─────────────────────────────────────────────────────────────────────────────
-- V29: Seed `emotes_convincer` — fourth unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks] sweep. Prior pairings: V25 paired
-- `BUST_DEALT_5` → `emotes_eliminator`, V27 paired `TRIPLE_UP` →
-- `emotes_baller`, V28 paired `NO_BUST_100` → `emotes_iron_stack`. This
-- row covers the bluff-energy pairing the todo + the V28 note both named
-- explicitly ("comeback-energy `WIN_BY_FOLD_10` pack").
--
-- Pairing rationale: `AchievementId.WIN_BY_FOLD_10` ("The convincer", RARE,
-- EITHER mode) currently grants 200 XP only. The narrative beat (won 10
-- pots without ever reaching showdown — i.e. bluffed or pressured every
-- opponent into folding) maps to a bluff / magician / sly-look emote set
-- for the seat that just talked everyone out of their chips.
--
-- Emoji set is `🪄 🎩 😏 🤫` — none overlap with the four purchasable
-- packs (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal
-- 👑🃏♠️♥️) or the prior unlock-only packs (eliminator 🪦⚰️👻🥀,
-- baller 💸💎🤑📈, iron stack 🛡️🧱🗿🦾), so the blast pool grows
-- by four real options when the pack lands in inventory. 🪄 doubles as
-- the pack icon emoji (mirrors the achievement's icon — same role 🪦 /
-- 💸 / 🛡️ play on the prior packs, so a user landing in the unlock
-- callout pattern-matches "I just earned The convincer" with "I just
-- unlocked the Convincer pack"). Picked 🎩 (top hat — illusionist) over
-- 🎭 (theatre masks, already in drama pack); picked 😏 (smirk — sly
-- read) over 😉 (winks read too friendly for "I just bluffed you off a
-- pot"); picked 🤫 (shushing face — keep your read to yourself) over
-- 🙊 (no-speak monkey, reads as embarrassment rather than discipline).
--
-- Follows the V25 / V27 / V28 / V20 unlock-only-cosmetic pattern:
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
-- Sort order 370 sits directly after the V28 iron stack pack (360), V27
-- baller pack (350), and V25 eliminator pack (340), so the unlock-only
-- emote packs are visually grouped if the shelf ever surfaces them in
-- catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_convincer" → 🪄🎩😏🤫`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"WIN_BY_FOLD_10" → "emotes_convincer"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.WIN_BY_FOLD_10)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_convincer', 'chip_offer', 370, '🪄', FALSE, NULL,
    '{"en":"Convincer Emote Pack","es":"Paquete del convincente"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by winning 10 pots without ever reaching showdown. Unlocks 🪄 🎩 😏 🤫 — for the seat that talked everyone else out of their chips. Send from the in-game emote tray.","es":"Desbloqueado al ganar 10 botes sin llegar a la confrontación. Desbloquea 🪄 🎩 😏 🤫 — para el asiento que convenció al resto de retirarse. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.convincer', 0, 1, FALSE, TRUE
);
