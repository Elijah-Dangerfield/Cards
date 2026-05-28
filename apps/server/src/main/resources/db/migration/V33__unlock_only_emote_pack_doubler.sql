-- ─────────────────────────────────────────────────────────────────────────────
-- V33: Seed `emotes_doubler` — seventh unlock-only emote pack.
--
-- Continues the [docs/todo.md §A "Catalog gating — unlock-only vs purchasable"
-- — More emote / blast-pack unlocks] sweep. Prior pairings: V25 paired
-- `BUST_DEALT_5` → `emotes_eliminator`, V27 paired `TRIPLE_UP` →
-- `emotes_baller`, V28 paired `NO_BUST_100` → `emotes_iron_stack`, V29
-- paired `WIN_BY_FOLD_10` → `emotes_convincer`, V30 paired `GOOD_FOLD_25`
-- → `emotes_disciplined`, V32 paired `HANDS_1000` → `emotes_grinder`.
--
-- V32's commit footer named three remaining candidate pairings:
-- `BEAT_*_10` (rejected — needs five new packs in one ship to avoid an
-- inconsistent half-rollout), `CHALLENGING_10_WINS` (deferred — risks
-- emoji collisions with `emotes_fierce`), and `DOUBLE_UP` ("mid-tier
-- doubling — pairs nicely with a 'Doubler' pack if emoji overlap with
-- the achievement's own 📊 icon can be avoided"). This row picks the
-- third option — the collision risk is bounded to a single icon and
-- easily mitigated by choosing a non-overlapping emoji set.
--
-- `AchievementId.DOUBLE_UP` ("Doubled up", RARE, EITHER mode, icon 📊)
-- is the last unpaired RARE-tier stack-swing achievement: TRIPLE_UP
-- (EPIC) already pairs to `emotes_baller`, but the doubling milestone
-- — meaningful on its own — has been carrying just an XP reward since
-- V1 launched. Every other RARE emote-pack achievement (BUST_DEALT_5,
-- WIN_BY_FOLD_10, GOOD_FOLD_25) carries a cosmetic complement; this
-- closes the gap.
--
-- Pairing rationale: doubling up captures the "I just made my move and
-- got paid" moment — confidence, ascent, calling shots. The "Doubler"
-- archetype gets emojis a player can blast to celebrate or trash-talk
-- after a chip-stack-doubling hand. Set is 🚀 ⏫ 🎯 💰:
--   * 🚀 rocket — the universal "up only" signal; matches the doubling
--     trajectory.
--   * ⏫ double-arrow-up — literal doubling indicator; the most
--     evocative glyph of the four and chosen as the pack icon.
--   * 🎯 target — "called my shot" — pairs with a player who picks
--     their spot and lands it.
--   * 💰 money bag — the chip-stack payoff in tangible form. Differs
--     from `emotes_baller`'s 💸 (money with wings) and 💎 (gem) by
--     reading as "earned cash sitting in front of you" rather than
--     "money in motion / wealth showcase."
--
-- None of those four overlap any of the ten prior pack sets (drama
-- 💃🧂🎭🤦, cute 🥺🥰😇🤗, fierce 😤🔥💀😎, royal 👑🃏♠️♥️, eliminator
-- 🪦⚰️👻🥀, baller 💸💎🤑📈, iron stack 🛡️🧱🗿🦾, convincer 🪄🎩😏🤫,
-- disciplined 🧘🦉👁️🪞, grinder ☕⛏️🛠️⌛). They also don't collide
-- with any achievement icon — explicitly: avoided 📊 (DOUBLE_UP itself),
-- 💹 (TRIPLE_UP), 🏆 (HANDS_1000), 📈 (already in `emotes_baller`).
-- Achievement DOUBLE_UP's own icon stays 📊; the pack icon ⏫ is the
-- differentiator that the unlock callout flags as "new cosmetic"
-- without the player thinking they just received a duplicate of the
-- achievement medallion.
--
-- Mode: DOUBLE_UP carries `mode = AchievementMode.EITHER` (defaults to
-- it — no override in `AchievementRegistry.kt:362`) so the grant fires
-- whether the doubling hand lands at a bot table or an MP table. Same
-- call as HANDS_1000 in V32: the achievement is "you doubled up," not
-- "you doubled up against bots." Once Phase 4.2 server-authoritative
-- gameplay lands, the MP path fires server-side and the BOTS path via
-- the client POST — `ClientGrantableAchievements.Default` keeps
-- DOUBLE_UP in `clientGrantable` (not `serverWitnessed`) because the
-- counter is on-device today.
--
-- Follows the V20 / V25 / V27 / V28 / V29 / V30 / V32 unlock-only-
-- cosmetic pattern:
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
-- Sort order 400 sits directly after the V32 grinder pack (390), so
-- the unlock-only emote packs are visually grouped if the shelf ever
-- surfaces them in catalog order.
--
-- Client wiring lands in the same commit:
--   * `EmojiPackCatalog.PackEmojis` gains `"emotes_doubler" → 🚀⏫🎯💰`.
--   * `ClientGrantableAchievements.Default.clientGrantable` gains
--     `"DOUBLE_UP" → "emotes_doubler"` so the client grant POST
--     resolves to this row.
--   * `cosmeticRewardFor(AchievementId.DOUBLE_UP)` mirrors the
--     mapping for the in-game unlock callout.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES (
    'emotes_doubler', 'chip_offer', 400, '⏫', FALSE, NULL,
    '{"en":"Doubler Emote Pack","es":"Paquete del doblador"}'::jsonb,
    '{"en":"Emote pack · earned","es":"Paquete de emotes · ganado"}'::jsonb,
    '{"en":"Unlocked by finishing a hand with 2× the chips you started it with — the moment everyone at the table notices. Unlocks 🚀 ⏫ 🎯 💰 — for the seat that just called their shot. Send from the in-game emote tray.","es":"Desbloqueado al terminar una mano con el doble de fichas con las que la empezaste — el momento en que toda la mesa se da cuenta. Desbloquea 🚀 ⏫ 🎯 💰 — para el asiento que acaba de cantar su jugada. Envíalas desde la bandeja de emotes."}'::jsonb,
    'emotes.doubler', 0, 1, FALSE, TRUE
);
