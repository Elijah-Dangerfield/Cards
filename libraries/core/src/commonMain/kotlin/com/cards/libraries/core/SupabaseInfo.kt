package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

object SupabaseInfo {
    val projectId: String
        get() = CardsBuildConfig.SUPABASE_PROJECT_ID

    val url: String
        get() = CardsBuildConfig.SUPABASE_URL

    val anonKey: String
        get() = CardsBuildConfig.SUPABASE_ANON_KEY
}
