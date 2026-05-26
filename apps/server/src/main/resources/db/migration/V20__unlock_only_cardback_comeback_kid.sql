-- ─────────────────────────────────────────────────────────────────────────────
-- V20: Seed an unlock-only "Comeback Kid" card back tied to
-- `AchievementId.DONT_CALL_IT_COMEBACK`.
--
-- Continues the [docs/todo.md §A "Seed an opening pool of unlock-only catalog
-- content"] sweep. V17 seeded the first two unlock-only titles (POT_5000 →
-- `title_pot_magnet`, COMEBACK_FROM_5BB → `title_short_stack_hero`); this row
-- adds the first earnable card back so the unlock-only catalog flexes across
-- two cosmetic shapes rather than just titles.
--
-- Pairing rationale: DONT_CALL_IT_COMEBACK is the second comeback EPIC and
-- still without an unlock reward (COMEBACK_FROM_5BB already maps to
-- `title_short_stack_hero`). Tying it to a *card back* — a different slot
-- from the existing title — also avoids stacking three near-identical
-- "you came back" titles in the same family, which was the deferral
-- rationale in V17.
--
-- Sort order 540 places this row directly below the existing card-back
-- shoppables (500–530) so a future admin toggle to make it purchasable
-- would land it in the right shelf neighborhood. `unlock_only = TRUE`
-- keeps it filtered out of the shop catalog today.
-- `cost_chips = 0` because the `chip_offer` kind constraint requires a
-- non-null cost; the unlock_only flag is what actually keeps it off the
-- shelf. `is_equippable = TRUE` so once granted the user can toggle it on
-- from My Items.
--
-- Client wiring lands in the same commit:
--   * `CardBackStyle.ComebackKid` + `paletteFor(...)` palette (warm
--     ember→flame→gold gradient — reads as "rising from the ashes").
--   * `cardBackForProductId("cardback_comeback_kid") = CardBackStyle.ComebackKid`.
--   * `ClientGrantableAchievements.Default` gains the DONT_CALL_IT_COMEBACK
--     → cardback_comeback_kid entry so client unlocks land an inventory
--     row via `POST /v1/me/grants/achievement/{id}`.
--
-- No backfill for users who already earned DONT_CALL_IT_COMEBACK before this
-- migration. The unlock event fires once at the moment of the qualifying
-- hand and posts to the grant endpoint then; existing earners stay without
-- the card back unless they re-trigger. This matches the V17 precedent
-- (POT_5000 / COMEBACK_FROM_5BB earners pre-V17 didn't get those titles
-- either) — formalising a server-side backfill needs server-side
-- achievement tracking, which is gated on Phase 4.2.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level, is_equippable, unlock_only
) VALUES
(
    'cardback_comeback_kid', 'chip_offer', 540, '🔥', FALSE, NULL,
    '{"en":"Comeback Kid Card Back","es":"Reverso del regreso"}'::jsonb,
    '{"en":"Card back · earned","es":"Reverso · ganado"}'::jsonb,
    '{"en":"Unlocked by dropping to 10 big blinds or fewer and building back to 100. Phoenix-tone card back — equip from your items."}'::jsonb,
    'cardback.comeback_kid', 0, 1, TRUE, TRUE
);
