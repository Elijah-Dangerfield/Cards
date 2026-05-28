-- ─────────────────────────────────────────────────────────────────────────────
-- V30: Seed `emotes_disciplined` — fifth unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks] sweep. Prior pairings: V25 paired
-- `BUST_DEALT_5` → `emotes_eliminator`, V27 paired `TRIPLE_UP` →
-- `emotes_baller`, V28 paired `NO_BUST_100` → `emotes_iron_stack`, V29
-- paired `WIN_BY_FOLD_10` → `emotes_convincer`. This row covers the
-- discipline pairing the todo named explicitly as the next candidate
-- ("complement to the Convincer pack's bluff energy — 'I read you cold
-- and folded'").
--
-- Pairing rationale: `AchievementId.GOOD_FOLD_25` ("Disciplined", RARE,
-- EITHER mode, isMystery) currently grants 200 XP only. The narrative
-- beat (25 hindsight-correct folds — read the table, knew the showdown
-- would have lost, and bowed out before chips went in) maps to a
-- patient / observant / centered emote set for the seat that just
-- watched everyone else commit and quietly stayed alive.
--
-- Emoji set is `🧘 🦉 👁️ 🪞` — none overlap with the four purchasable
-- packs (drama 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal
-- 👑🃏♠️♥️) or the prior unlock-only packs (eliminator 🪦⚰️👻🥀,
-- baller 💸💎🤑📈, iron stack 🛡️🧱🗿🦾, convincer 🪄🎩😏🤫), so the
-- blast pool grows by four real options when the pack lands in
-- inventory. 🦉 is the pack icon emoji (wise observer — "I saw it
-- coming, and walked away"); the achievement's own icon 🧘 is reused
-- by `NO_BUST_50` so we pick a unique icon to avoid pack/achievement
-- icon collision while keeping 🧘 in the emoji set so the unlock
-- callout still pattern-matches the achievement. Picked 🦉 (owl —
-- watchful patience) over 🧠 (already serves as the FIRST_GOOD_FOLD
-- achievement icon, would over-overlap the family). Picked 👁️ (single
-- eye — "I read you") over 👀 (two eyes — reads as gossip/lookie-loo,
-- wrong vibe for hindsight discipline). Picked 🪞 (mirror — self-
-- knowledge / table-read) over 🔮 (crystal ball — reads as prediction
-- rather than read).
--
-- Follows the V25 / V27 / V28 / V29 / V20 unlock-only-cosmetic pattern:
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
-- Sort order 380 sits directly after the V29 convincer pack (370), V28
-- iron stack pack (360), V27 baller pack (350), and V25 eliminator pack
-- (340), so the unlock-only emote packs are visually grouped if the
-- shelf ever surfaces them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_disciplined" → 🧘🦉👁️🪞`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"GOOD_FOLD_25" → "emotes_disciplined"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.GOOD_FOLD_25)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_disciplined', 'chip_offer', 380, '🦉', FALSE, NULL,
    '{"en":"Disciplined Emote Pack","es":"Paquete del disciplinado"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by making 25 hindsight-correct folds — hands you would have lost at showdown. Unlocks 🧘 🦉 👁️ 🪞 — for the seat that read the table and quietly stayed alive. Send from the in-game emote tray.","es":"Desbloqueado al hacer 25 retiradas correctas en retrospectiva — manos que habrías perdido en la confrontación. Desbloquea 🧘 🦉 👁️ 🪞 — para el asiento que leyó la mesa y se mantuvo vivo en silencio. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.disciplined', 0, 1, FALSE, TRUE
);
