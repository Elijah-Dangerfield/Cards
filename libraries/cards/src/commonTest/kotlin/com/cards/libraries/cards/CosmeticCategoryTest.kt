package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the product-id prefix → slot mapping that shop auto-equip and the
 * My Items "Only visible to you" badge depend on. Drift between this
 * helper and the server-side `V5__products.sql` id conventions would
 * silently misclassify cosmetics — the shop would either fail to
 * auto-equip a felt or label a public title as personal-only. Both are
 * silent UX bugs.
 */
class CosmeticCategoryTest {

    // ---------- cosmeticSlotFor ----------

    @Test
    fun cosmeticSlotFor_feltPrefix_isFelt() {
        assertEquals(CosmeticSlot.Felt, cosmeticSlotFor("felt_royal_red"))
        assertEquals(CosmeticSlot.Felt, cosmeticSlotFor("felt_pine_green"))
    }

    @Test
    fun cosmeticSlotFor_tablePrefix_isFelt() {
        assertEquals(CosmeticSlot.Felt, cosmeticSlotFor("table_neon"))
        assertEquals(CosmeticSlot.Felt, cosmeticSlotFor("table_sunset"))
    }

    @Test
    fun cosmeticSlotFor_cardbackPrefix_isCardBack() {
        assertEquals(CosmeticSlot.CardBack, cosmeticSlotFor("cardback_marble"))
        assertEquals(CosmeticSlot.CardBack, cosmeticSlotFor("cardback_comeback_kid"))
    }

    @Test
    fun cosmeticSlotFor_titlePrefix_isTitle() {
        assertEquals(CosmeticSlot.Title, cosmeticSlotFor("title_pot_magnet"))
        assertEquals(CosmeticSlot.Title, cosmeticSlotFor("title_felt_veteran"))
        assertEquals(CosmeticSlot.Title, cosmeticSlotFor("title_bot_whisperer"))
    }

    @Test
    fun cosmeticSlotFor_toolPrefix_isTool() {
        assertEquals(CosmeticSlot.Tool, cosmeticSlotFor("tool_win_odds"))
        assertEquals(CosmeticSlot.Tool, cosmeticSlotFor("tool_opponent_style"))
    }

    @Test
    fun cosmeticSlotFor_badgePrefix_isBadge() {
        assertEquals(CosmeticSlot.Badge, cosmeticSlotFor("badge_founding_member_1000"))
    }

    @Test
    fun cosmeticSlotFor_chipPackPrefix_hasNoSlot() {
        assertNull(cosmeticSlotFor("chip_pack_small"))
        assertNull(cosmeticSlotFor("chip_pack_mega"))
        assertNull(cosmeticSlotFor("chip_pack_flash_sale"))
    }

    @Test
    fun cosmeticSlotFor_emotesPrefix_hasNoSlot() {
        assertNull(cosmeticSlotFor("emotes_eliminator"))
        assertNull(cosmeticSlotFor("emotes_tactician"))
    }

    @Test
    fun cosmeticSlotFor_avatarsPrefix_hasNoSlot() {
        assertNull(cosmeticSlotFor("avatars_food"))
        assertNull(cosmeticSlotFor("avatars_animals"))
    }

    @Test
    fun cosmeticSlotFor_unknownPrefix_hasNoSlot() {
        assertNull(cosmeticSlotFor("totally_unknown"))
        assertNull(cosmeticSlotFor(""))
        assertNull(cosmeticSlotFor("FELT_royal_red"))
    }

    // ---------- isPersonalCosmetic ----------

    @Test
    fun isPersonalCosmetic_felt_isPrivate() {
        assertEquals(true, isPersonalCosmetic("felt_royal_red"))
        assertEquals(true, isPersonalCosmetic("table_neon"))
    }

    @Test
    fun isPersonalCosmetic_cardback_isPrivate() {
        assertEquals(true, isPersonalCosmetic("cardback_marble"))
    }

    @Test
    fun isPersonalCosmetic_tool_isPrivate() {
        assertEquals(true, isPersonalCosmetic("tool_win_odds"))
    }

    @Test
    fun isPersonalCosmetic_title_isPublic() {
        assertEquals(false, isPersonalCosmetic("title_pot_magnet"))
        assertEquals(false, isPersonalCosmetic("title_bot_whisperer"))
    }

    @Test
    fun isPersonalCosmetic_badge_isPublic() {
        assertEquals(false, isPersonalCosmetic("badge_founding_member_1000"))
    }

    @Test
    fun isPersonalCosmetic_unknownPrefix_defaultsToPublic() {
        assertEquals(false, isPersonalCosmetic("totally_unknown"))
        assertEquals(false, isPersonalCosmetic("chip_pack_small"))
        assertEquals(false, isPersonalCosmetic("emotes_eliminator"))
    }
}
