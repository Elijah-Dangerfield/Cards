-- V78: Rewrite shop-visible product copy so it reads like a person wrote it.
--
-- The seeded descriptions (V5 / V14 / V23 / V24 / V46 / V57 / V71 ...) lean
-- hard on the em dash as a connector and repeat the same "Equip from your
-- items." tail on nearly every cosmetic. That cadence reads as machine-
-- written. This pass replaces the em dashes with ordinary punctuation and
-- trims the filler while preserving every factual claim (emoji rosters,
-- self-only visibility, achievement criteria copy, the doubling mechanic).
--
-- Scope: shop-visible products only (kind = 'chip_pack' carries no
-- description; the unlock-only achievement-reward copy still names its
-- criteria and is reviewed separately under ENG-3). Append-only UPDATEs over
-- the existing rows so dev Flyway checksums stay intact; GET /v1/products
-- reflects the new copy on next deploy after the catalog cache rolls.
--
-- Prod carries the same rows and must end up with identical copy; that mirror
-- is a reviewed, credentialed write and stays with the human (ENG-3).

-- Felts ----------------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Deep red felt for your playing surface. Visible to you only in solo games."}'::jsonb
WHERE id = 'felt_royal_red';

UPDATE products
SET description_by_locale = '{"en":"Deep blue felt that stays easy on the eyes through long sessions."}'::jsonb
WHERE id = 'felt_midnight_blue';

UPDATE products
SET description_by_locale = '{"en":"A moody black felt for the surface."}'::jsonb
WHERE id = 'felt_charcoal';

UPDATE products
SET description_by_locale = '{"en":"Muted pine-green felt. Slate, not casino. Visible to you only in solo games."}'::jsonb
WHERE id = 'felt_pine_green';

UPDATE products
SET description_by_locale = '{"en":"Warm sunset-orange felt at half price this weekend. Once you own it, it stays yours; the sale window only limits the discount."}'::jsonb
WHERE id = 'felt_sunset_weekend';

-- Emote packs ----------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Unlocks 💃 🧂 🎭 🤦 to send as big, screen-filling reactions from the in-game emote tray.","es":"Desbloquea 💃 🧂 🎭 🤦 para enviarlas desde la bandeja de emotes dentro del juego."}'::jsonb
WHERE id = 'emotes_drama';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🥺 🥰 😇 🤗, soft reactions for friendly tables. Send them from the in-game emote tray.","es":"Desbloquea 🥺 🥰 😇 🤗, reacciones suaves para mesas amistosas. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_cute';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 😤 🔥 💀 😎, heat for the bluffers. Send them from the in-game emote tray.","es":"Desbloquea 😤 🔥 💀 😎, intensidad para los faroleros. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_fierce';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 👑 🃏 ♠️ ♥️, high-roller-coded reactions. Send them from the in-game emote tray.","es":"Desbloquea 👑 🃏 ♠️ ♥️, reacciones de gran apostador. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_royal';

-- Avatar packs ---------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🐱 🐶 🐯 🐼 🦊 🐻 🦁 🐸 as avatar choices in your profile. Pick the one you wear at the table."}'::jsonb
WHERE id = 'avatars_animals';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🍕 🍔 🌮 🍣 🍰 🥑 🍩 ☕ as avatar choices. Become The Taco Player."}'::jsonb
WHERE id = 'avatars_food';

UPDATE products
SET description_by_locale = '{"en":"Unlocks ⚽ 🏀 🏈 ⚾ 🎾 🎯 🎳 🥊 as avatar choices."}'::jsonb
WHERE id = 'avatars_sports';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🧙 🧚 🧛 🧜 🦄 🐉 🧞 🐲 as avatar choices."}'::jsonb
WHERE id = 'avatars_fantasy';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🦖 🐙 🦕 🦑 🦞 🦀 🐡 🦈 as avatar choices."}'::jsonb
WHERE id = 'avatars_mythical';

-- Card backs -----------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"A marble-pattern back for your hole cards."}'::jsonb
WHERE id = 'cardback_marble';

UPDATE products
SET description_by_locale = '{"en":"A glinting gold-foil pattern for your hole cards."}'::jsonb
WHERE id = 'cardback_gold';

UPDATE products
SET description_by_locale = '{"en":"A glowing neon-line pattern for your hole cards."}'::jsonb
WHERE id = 'cardback_neon';

UPDATE products
SET description_by_locale = '{"en":"A crystalline diamond pattern for high-roller decks."}'::jsonb
WHERE id = 'cardback_diamond';

UPDATE products
SET description_by_locale = '{"en":"A crimson-on-black skull motif for the bold."}'::jsonb
WHERE id = 'cardback_skulls';

UPDATE products
SET description_by_locale = '{"en":"An indigo-and-magenta gradient with a centered star sparkle."}'::jsonb
WHERE id = 'cardback_galaxy';

UPDATE products
SET description_by_locale = '{"en":"An animated rainbow shimmer that slowly cycles across the card."}'::jsonb
WHERE id = 'cardback_holographic';

UPDATE products
SET description_by_locale = '{"en":"A black-to-ember vertical gradient for the heaters."}'::jsonb
WHERE id = 'cardback_fire';

UPDATE products
SET description_by_locale = '{"en":"Your avatar emoji on your avatar color. It renders only on your own hole cards; opponents see a default back."}'::jsonb
WHERE id = 'cardback_avatar';

UPDATE products
SET description_by_locale = '{"en":"A Bicycle-red field under a white diamond lattice. Classic poker-night look."}'::jsonb
WHERE id = 'cardback_lattice';

UPDATE products
SET description_by_locale = '{"en":"A charcoal-black field with parallel gold diagonals, all art-deco menu energy."}'::jsonb
WHERE id = 'cardback_hatching';

UPDATE products
SET description_by_locale = '{"en":"An emerald field under an orthogonal tan grid, for a billiards-room feel."}'::jsonb
WHERE id = 'cardback_crosshatch';

UPDATE products
SET description_by_locale = '{"en":"A deep-navy field with an offset cream dot grid. Clean and modern."}'::jsonb
WHERE id = 'cardback_dots';

UPDATE products
SET description_by_locale = '{"en":"A charcoal field with fine off-white vertical pinstripes, the tailored-suit card."}'::jsonb
WHERE id = 'cardback_pinstripes';

-- Player titles --------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Shows under your name at the table for everyone to see. Pure flex."}'::jsonb
WHERE id = 'title_bluff_master';

UPDATE products
SET description_by_locale = '{"en":"For the player who reads the table. Shows under your name."}'::jsonb
WHERE id = 'title_shark';

UPDATE products
SET description_by_locale = '{"en":"A rare title for the dedicated. Shows under your name."}'::jsonb
WHERE id = 'title_high_roller';

-- Utilities ------------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Shows your live win percentage during a hand, worked out from your hole cards and the visible board. It is the same math you could do yourself; we just do it for you."}'::jsonb
WHERE id = 'tool_win_odds';

UPDATE products
SET description_by_locale = '{"en":"A heat-map summary of each human opponent''s prior public play (raise, call, and fold tendencies). Tapping a bot opponent already shows its style for free; this unlocks the same read on human opponents in multiplayer. It is all public info; we just keep the receipts."}'::jsonb
WHERE id = 'tool_opponent_style';

-- XP boost -------------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Earn double XP on every hand you play for 5 minutes. Activate it from your profile whenever you are ready, and buy more to keep a few on hand.","es":"Gana el doble de XP en cada mano que juegues durante 5 minutos. Actívalo desde tu perfil cuando quieras, y compra más para tener algunos a mano."}'::jsonb
WHERE id = 'boost_xp_2x';
