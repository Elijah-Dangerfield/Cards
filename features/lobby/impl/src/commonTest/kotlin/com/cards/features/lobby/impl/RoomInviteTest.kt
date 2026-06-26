package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.features.lobby.RoomInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomInviteTest {

    @Test
    fun linkForCode_appends_the_code_to_the_deep_link_base_path() {
        assertEquals("cards://join/ABC123", RoomInvite.linkForCode("ABC123"))
    }

    @Test
    fun base_path_is_the_cards_join_scheme() {
        assertEquals("cards://join", RoomInvite.DEEP_LINK_BASE_PATH)
    }

    @Test
    fun link_starts_with_the_base_path_so_the_share_url_matches_the_registered_deep_link() {
        // LobbyFeatureEntryPoint registers `${DEEP_LINK_BASE_PATH}/{prefilledCode}`;
        // the shared link must sit under that base or a tapped invite won't resolve.
        val link = RoomInvite.linkForCode("ZZZ999")
        assertTrue(link.startsWith("${RoomInvite.DEEP_LINK_BASE_PATH}/"))
        assertEquals("ZZZ999", link.removePrefix("${RoomInvite.DEEP_LINK_BASE_PATH}/"))
    }
}
