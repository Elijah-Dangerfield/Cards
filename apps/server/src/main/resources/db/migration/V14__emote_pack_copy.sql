-- V14: Realign emote-pack copy with the non-equippable model.
--
-- V12 marked emote packs as `is_equippable = FALSE` because owning the
-- pack just unlocks the reactions for sending from the in-game tray
-- (no per-emote equip toggle, no slot). The V5 seed descriptions still
-- told users to "Equip from your items" — that's misleading on two
-- fronts:
--   1. The store sheet's body copy + the My items row both honor
--      `isEquippable`, so the user never sees an Equip button for
--      emote packs. The description was promising a UI affordance
--      that doesn't exist.
--   2. The post-purchase Owned-sheet copy now routes by item type
--      (PurchaseConfirmSheet.ownedBodyFor) and points emote-pack
--      buyers at the in-game tray. The description should land them
--      in the same mental model before they buy.
--
-- Avatar packs already had correct copy in V5 ("as avatar choices in
-- your profile") so they're left alone.
--
-- ES translations updated alongside EN — the catalog matcher falls
-- back to EN, but Spanish-locale users should see the corrected
-- phrasing rather than the older "Equip" wording.

UPDATE products
SET description_by_locale = '{"en":"Unlocks 💃 🧂 🎭 🤦 — send big, screen-filling reactions from the in-game emote tray.","es":"Desbloquea 💃 🧂 🎭 🤦 — envíalas desde la bandeja de emotes dentro del juego."}'::jsonb
WHERE id = 'emotes_drama';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 🥺 🥰 😇 🤗 — soft-pawed reactions for friendly tables. Send from the in-game emote tray.","es":"Desbloquea 🥺 🥰 😇 🤗 — reacciones suaves para mesas amistosas. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_cute';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 😤 🔥 💀 😎 — heat for the bluffers. Send from the in-game emote tray.","es":"Desbloquea 😤 🔥 💀 😎 — intensidad para los faroleros. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_fierce';

UPDATE products
SET description_by_locale = '{"en":"Unlocks 👑 🃏 ♠️ ♥️ — high-roller-coded reactions. Send from the in-game emote tray.","es":"Desbloquea 👑 🃏 ♠️ ♥️ — reacciones de gran apostador. Envíalas desde la bandeja de emotes."}'::jsonb
WHERE id = 'emotes_royal';
