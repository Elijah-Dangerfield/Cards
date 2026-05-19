-- V5: Server-side product catalog.
--
-- Moves the shop catalog out of `InMemoryProductCatalogSource` and into
-- Postgres so an admin can add / remove / re-price items without a server
-- redeploy. The endpoint, the wire format, and the client cache stay
-- unchanged — only the source of truth moves.
--
-- One row per shop product. The two product kinds (chip_pack IAPs and
-- chip_offer cosmetics) share a table with a `kind` discriminator and
-- nullable columns for the kind-specific fields. We chose a single table
-- over inheritance/separate tables because:
--   * The catalog is read in one shot per `GET /v1/products` call.
--   * The kinds share 90% of their columns (id, icon, localized strings,
--     platforms, availability) — separate tables would duplicate them.
--   * Admin UIs (future) want a single "products" surface to browse.
--
-- Localized strings live in JSONB maps keyed by BCP-47 locale tag. The
-- existing `LocaleMatch.kt` matcher already operates on such a map shape,
-- so the application layer just deserializes the column straight into the
-- domain model. English is required as the fallback locale.
--
-- Platforms is stored as a TEXT[] containing the enum names from
-- `ClientContext.Platform` ('Android', 'iOS', 'Other'). Postgres native
-- arrays let us add platform-exclusive products later without a schema
-- change. Most products are sold on all three platforms.
--
-- `available_until_epoch_ms` is wall-clock-anchored (UTC milliseconds
-- since epoch). NULL = always purchasable. Server filters expired offers
-- by its own clock so client clock manipulation can't make an expired
-- offer re-appear.

CREATE TABLE products (
    id                          TEXT PRIMARY KEY,
    kind                        TEXT NOT NULL,
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    icon_emoji                  TEXT NOT NULL,
    featured                    BOOLEAN NOT NULL DEFAULT FALSE,
    available_until_epoch_ms    BIGINT,
    platforms                   TEXT[] NOT NULL DEFAULT ARRAY['Android', 'iOS', 'Other'],

    -- Shared localized strings.
    title_by_locale             JSONB NOT NULL,
    subtitle_by_locale          JSONB NOT NULL,
    badge_by_locale             JSONB,

    -- ChipPack-only.
    grants_chips                BIGINT,
    ios_sku                     TEXT,
    ios_fallback_price          TEXT,
    android_sku                 TEXT,
    android_fallback_price      TEXT,

    -- ChipOffer-only.
    grants_key                  TEXT,
    cost_chips                  BIGINT,
    unlock_level                INTEGER,
    description_by_locale       JSONB,

    CONSTRAINT products_kind_check CHECK (kind IN ('chip_pack', 'chip_offer')),
    CONSTRAINT products_chip_pack_fields CHECK (
        kind <> 'chip_pack' OR (
            grants_chips IS NOT NULL
            AND ios_sku IS NOT NULL
            AND ios_fallback_price IS NOT NULL
            AND android_sku IS NOT NULL
            AND android_fallback_price IS NOT NULL
        )
    ),
    CONSTRAINT products_chip_offer_fields CHECK (
        kind <> 'chip_offer' OR (
            grants_key IS NOT NULL
            AND cost_chips IS NOT NULL
        )
    )
);

-- The catalog is small (tens of rows) and read in full per request,
-- so the index is mostly so the query plan can stream rows in display
-- order without a sort node.
CREATE INDEX products_sort_idx ON products (sort_order);


-- ---------------------------------------------------------------------------
-- Seed: V1 catalog mirroring InMemoryProductCatalogSource as of 2026-05-19.
--
-- Demo time-limited offers (`chip_pack_flash_sale`, `felt_sunset_weekend`)
-- are seeded with `available_until_epoch_ms = NULL` so their products stay
-- in the catalog without the synthetic countdown that the in-memory source
-- previously generated at server startup. To stage a real time-limited
-- promotion, an admin updates this column with an actual wall-clock epoch.
-- ---------------------------------------------------------------------------

-- Chip packs (IAP) -----------------------------------------------------------

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale,
    grants_chips, ios_sku, ios_fallback_price, android_sku, android_fallback_price
) VALUES
(
    'chip_pack_small', 'chip_pack', 100, '🪙', FALSE, NULL,
    '{"en":"Pocket Stack","es":"Pila de bolsillo"}'::jsonb,
    '{"en":"1,000 chips","es":"1.000 fichas"}'::jsonb,
    1000, 'com.cards.iap.chips.small', '$0.99', 'chips_small', '$0.99'
),
(
    'chip_pack_medium', 'chip_pack', 110, '💰', TRUE,
    '{"en":"BEST VALUE","es":"MEJOR VALOR"}'::jsonb,
    '{"en":"Tall Stack","es":"Pila alta"}'::jsonb,
    '{"en":"15,000 chips","es":"15.000 fichas"}'::jsonb,
    15000, 'com.cards.iap.chips.medium', '$4.99', 'chips_medium', '$4.99'
),
(
    'chip_pack_large', 'chip_pack', 120, '🐋', FALSE,
    '{"en":"+20% BONUS","es":"+20% EXTRA"}'::jsonb,
    '{"en":"Whale Stack","es":"Pila ballena"}'::jsonb,
    '{"en":"50,000 chips","es":"50.000 fichas"}'::jsonb,
    50000, 'com.cards.iap.chips.large', '$9.99', 'chips_large', '$9.99'
),
(
    'chip_pack_mega', 'chip_pack', 130, '👑', FALSE, NULL,
    '{"en":"High Roller","es":"Gran apostador"}'::jsonb,
    '{"en":"150,000 chips","es":"150.000 fichas"}'::jsonb,
    150000, 'com.cards.iap.chips.mega', '$19.99', 'chips_mega', '$19.99'
),
(
    -- Was the time-limited "Flash Sale" demo offer in the in-memory source.
    -- Kept in the catalog at the same SKU but no longer wall-clock-limited.
    'chip_pack_flash_sale', 'chip_pack', 140, '⚡', FALSE,
    '{"en":"LIMITED","es":"LIMITADO"}'::jsonb,
    '{"en":"Flash Sale","es":"Oferta relámpago"}'::jsonb,
    '{"en":"10,000 chips · 10×","es":"10.000 fichas · 10×"}'::jsonb,
    10000, 'com.cards.iap.chips.flash_sale', '$0.99', 'chips_flash_sale', '$0.99'
);

-- Chip offers (chip-priced cosmetics + utilities) ----------------------------

INSERT INTO products (
    id, kind, sort_order, icon_emoji, featured, badge_by_locale,
    title_by_locale, subtitle_by_locale, description_by_locale,
    grants_key, cost_chips, unlock_level
) VALUES
-- Felts (cheap, gateway purchase, level 1)
(
    'felt_royal_red', 'chip_offer', 200, '🟥', FALSE, NULL,
    '{"en":"Royal Red Felt","es":"Fieltro rojo real"}'::jsonb,
    '{"en":"Table felt","es":"Fieltro de mesa"}'::jsonb,
    '{"en":"Deep red felt for the playing surface. Equip from your items — visible to you only in solo games."}'::jsonb,
    'felt.royal_red', 1500, 1
),
(
    'felt_midnight_blue', 'chip_offer', 210, '🟦', FALSE, NULL,
    '{"en":"Midnight Blue Felt","es":"Fieltro azul medianoche"}'::jsonb,
    '{"en":"Table felt","es":"Fieltro de mesa"}'::jsonb,
    '{"en":"Deep blue felt — easy on the eyes during long sessions. Equip from your items."}'::jsonb,
    'felt.midnight_blue', 1500, 1
),
(
    'felt_charcoal', 'chip_offer', 220, '⬛', FALSE, NULL,
    '{"en":"Charcoal Felt","es":"Fieltro carbón"}'::jsonb,
    '{"en":"Table felt","es":"Fieltro de mesa"}'::jsonb,
    '{"en":"Moody black felt. Equip from your items."}'::jsonb,
    'felt.charcoal', 2000, 3
),
-- Emote packs (level 1-3, multiple emotes per pack)
(
    'emotes_drama', 'chip_offer', 300, '💃', FALSE, NULL,
    '{"en":"Drama Emote Pack","es":"Paquete drama"}'::jsonb,
    '{"en":"Emotes · 4 reactions","es":"Emotes · 4 reacciones"}'::jsonb,
    '{"en":"Unlocks 💃 🧂 🎭 🤦 — send big, screen-filling reactions to the table. Equip individually from your items."}'::jsonb,
    'emotes.drama', 3500, 1
),
(
    'emotes_cute', 'chip_offer', 310, '🥺', FALSE, NULL,
    '{"en":"Cute Emote Pack","es":"Paquete tierno"}'::jsonb,
    '{"en":"Emotes · 4 reactions","es":"Emotes · 4 reacciones"}'::jsonb,
    '{"en":"Unlocks 🥺 🥰 😇 🤗 — soft-pawed reactions for friendly tables. Equip from your items."}'::jsonb,
    'emotes.cute', 3500, 1
),
(
    'emotes_fierce', 'chip_offer', 320, '🔥', FALSE, NULL,
    '{"en":"Fierce Emote Pack","es":"Paquete feroz"}'::jsonb,
    '{"en":"Emotes · 4 reactions","es":"Emotes · 4 reacciones"}'::jsonb,
    '{"en":"Unlocks 😤 🔥 💀 😎 — heat for the bluffers. Equip from your items."}'::jsonb,
    'emotes.fierce', 4500, 5
),
(
    'emotes_royal', 'chip_offer', 330, '👑', FALSE, NULL,
    '{"en":"Royal Emote Pack","es":"Paquete real"}'::jsonb,
    '{"en":"Emotes · 4 reactions","es":"Emotes · 4 reacciones"}'::jsonb,
    '{"en":"Unlocks 👑 🃏 ♠️ ♥️ — high-roller-coded reactions. Equip from your items."}'::jsonb,
    'emotes.royal', 6000, 10
),
-- Avatar emoji packs
(
    'avatars_animals', 'chip_offer', 400, '🦊', FALSE, NULL,
    '{"en":"Animal Avatars","es":"Avatares animales"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"Unlocks 🐱 🐶 🐯 🐼 🦊 🐻 🦁 🐸 as avatar choices in your profile. Pick the one you wear at the table."}'::jsonb,
    'avatars.animals', 4000, 1
),
(
    'avatars_food', 'chip_offer', 410, '🍕', FALSE, NULL,
    '{"en":"Foodie Avatars","es":"Avatares foodie"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"Unlocks 🍕 🍔 🌮 🍣 🍰 🥑 🍩 ☕ as avatar choices. Become The Taco Player."}'::jsonb,
    'avatars.food', 4000, 1
),
(
    'avatars_sports', 'chip_offer', 420, '🏀', FALSE, NULL,
    '{"en":"Sports Avatars","es":"Avatares deportes"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"Unlocks ⚽ 🏀 🏈 ⚾ 🎾 🎯 🎳 🥊 as avatar choices."}'::jsonb,
    'avatars.sports', 4500, 3
),
(
    'avatars_fantasy', 'chip_offer', 430, '🧙', FALSE,
    '{"en":"POPULAR","es":"POPULAR"}'::jsonb,
    '{"en":"Fantasy Avatars","es":"Avatares fantasía"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"Unlocks 🧙 🧚 🧛 🧜 🦄 🐉 🧞 🐲 as avatar choices."}'::jsonb,
    'avatars.fantasy', 6000, 5
),
(
    'avatars_mythical', 'chip_offer', 440, '🦖', FALSE, NULL,
    '{"en":"Mythical Avatars","es":"Avatares míticos"}'::jsonb,
    '{"en":"Avatar pack · 8 emojis","es":"Paquete · 8 emojis"}'::jsonb,
    '{"en":"Unlocks 🦖 🐙 🦕 🦑 🦞 🦀 🐡 🦈 as avatar choices."}'::jsonb,
    'avatars.mythical', 9000, 15
),
-- Card backs
(
    'cardback_marble', 'chip_offer', 500, '🂠', FALSE,
    '{"en":"POPULAR","es":"POPULAR"}'::jsonb,
    '{"en":"Marble Card Back","es":"Reverso mármol"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Marble-pattern back for your hole cards. Equip from your items."}'::jsonb,
    'cardback.marble', 4000, 3
),
(
    'cardback_gold', 'chip_offer', 510, '🂠', FALSE, NULL,
    '{"en":"Gold Foil Card Back","es":"Reverso oro"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Glinting gold foil pattern. Equip from your items."}'::jsonb,
    'cardback.gold', 5000, 5
),
(
    'cardback_neon', 'chip_offer', 520, '🂠', FALSE, NULL,
    '{"en":"Neon Lines Card Back","es":"Reverso neón"}'::jsonb,
    '{"en":"Card back","es":"Reverso de carta"}'::jsonb,
    '{"en":"Glowing neon-line pattern. Equip from your items."}'::jsonb,
    'cardback.neon', 5000, 5
),
(
    'cardback_diamond', 'chip_offer', 530, '💎', FALSE, NULL,
    '{"en":"Diamond Lattice","es":"Rejilla diamante"}'::jsonb,
    '{"en":"Card back · Premium","es":"Reverso · Premium"}'::jsonb,
    '{"en":"Crystalline diamond pattern — for high-roller decks. Equip from your items."}'::jsonb,
    'cardback.diamond', 12000, 15
),
-- Premium table themes
(
    'table_neon', 'chip_offer', 600, '🎰', TRUE,
    '{"en":"NEW","es":"NUEVO"}'::jsonb,
    '{"en":"Neon Table","es":"Mesa de neón"}'::jsonb,
    '{"en":"Table theme","es":"Tema de mesa"}'::jsonb,
    '{"en":"Replaces the felt AND rail with a full neon table theme. Higher tier than a felt-only swap."}'::jsonb,
    'table.neon', 8000, 8
),
(
    'table_sunset', 'chip_offer', 610, '🌅', FALSE, NULL,
    '{"en":"Sunset Table","es":"Mesa atardecer"}'::jsonb,
    '{"en":"Table theme","es":"Tema de mesa"}'::jsonb,
    '{"en":"Warm sunset-orange theme. Felt + rail. Equip from your items."}'::jsonb,
    'table.sunset', 8000, 8
),
-- Player titles
(
    'title_bluff_master', 'chip_offer', 700, '🎭', FALSE, NULL,
    '{"en":"Bluff Master","es":"Maestro del farol"}'::jsonb,
    '{"en":"Player title","es":"Título de jugador"}'::jsonb,
    '{"en":"Shows under your name at the table for everyone to see. Pure flex."}'::jsonb,
    'title.bluff_master', 12000, 10
),
(
    'title_shark', 'chip_offer', 710, '🦈', FALSE, NULL,
    '{"en":"The Shark","es":"El tiburón"}'::jsonb,
    '{"en":"Player title","es":"Título de jugador"}'::jsonb,
    '{"en":"For the player who reads the table. Shows under your name."}'::jsonb,
    'title.shark', 18000, 15
),
(
    'title_high_roller', 'chip_offer', 720, '🏆', FALSE,
    '{"en":"RARE","es":"RARO"}'::jsonb,
    '{"en":"High Roller","es":"Apostador grande"}'::jsonb,
    '{"en":"Player title","es":"Título de jugador"}'::jsonb,
    '{"en":"Rare title for the dedicated. Shows under your name."}'::jsonb,
    'title.high_roller', 25000, 20
),
-- Math / utility unlocks
(
    'tool_win_odds', 'chip_offer', 800, '📊', FALSE, NULL,
    '{"en":"Win Odds Display","es":"Probabilidades"}'::jsonb,
    '{"en":"Utility","es":"Utilidad"}'::jsonb,
    '{"en":"Shows your live win percentage during a hand, computed from YOUR hole cards + the visible board. Same info you can work out yourself — we just do the math."}'::jsonb,
    'tool.win_odds', 10000, 10
),
(
    'tool_opponent_style', 'chip_offer', 810, '🔍', FALSE,
    '{"en":"RARE","es":"RARO"}'::jsonb,
    '{"en":"Opponent Style Reader","es":"Estilo de oponente"}'::jsonb,
    '{"en":"Utility","es":"Utilidad"}'::jsonb,
    '{"en":"Heat-map summary of each opponent''s prior public play (raise / call / fold tendencies). Public info — we just keep the receipts."}'::jsonb,
    'tool.opponent_style', 20000, 15
),
-- Was the time-limited "Weekend Sale" demo. Kept in the catalog at the same
-- product id; no longer wall-clock-limited. Admins can set
-- available_until_epoch_ms to stage a real sale.
(
    'felt_sunset_weekend', 'chip_offer', 900, '🌅', FALSE,
    '{"en":"50% OFF","es":"50% MENOS"}'::jsonb,
    '{"en":"Sunset Felt","es":"Fieltro atardecer"}'::jsonb,
    '{"en":"Table felt · weekend sale","es":"Fieltro · oferta"}'::jsonb,
    '{"en":"Warm sunset-orange felt. Weekend sale: half price. Once you own it, it''s yours — the sale window only limits the discount."}'::jsonb,
    'felt.sunset', 4000, 1
);
