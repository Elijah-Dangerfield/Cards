-- V46: Clarify that bot heat-maps are free via seat-tap; this utility
-- is the paid path for human opponents in multiplayer. The V5 seed
-- read as if the utility was required for every opponent, which hides
-- the fact that tapping a bot already surfaces its style for free.

UPDATE products
SET description_by_locale = '{"en":"Heat-map summary of each human opponent''s prior public play (raise / call / fold tendencies). Tap a bot opponent to see their style for free — this utility unlocks the same insight for human opponents in multiplayer. Public info; we just keep the receipts."}'::jsonb
WHERE id = 'tool_opponent_style';
