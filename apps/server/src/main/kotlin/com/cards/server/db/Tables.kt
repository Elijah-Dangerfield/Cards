package com.dangerfield.cards.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions that mirror `src/main/resources/db/migration/V1__profiles.sql`.
 *
 * **Flyway is the source of truth for the schema.** These definitions are
 * read-side projections — Exposed uses them to type-check our queries and
 * map rows to Kotlin values. The smoke test in `DatabaseSchemaTest` boots
 * Flyway against a Testcontainer Postgres and fails if the two get out of
 * sync.
 *
 * Auth-related tables (`auth.users`, refresh tokens, identities) live in
 * Supabase's `auth` schema and are managed by Supabase Auth, not us. We
 * never define them here — we only reference `auth.users(id)` by storing
 * UUIDs that match those rows. The link is enforced at the application
 * layer (we only insert profiles for users we've authenticated via a
 * valid Supabase JWT).
 */
object ProfilesTable : Table("profiles") {
    val userId = uuid("user_id")
    val displayName = text("display_name").uniqueIndex("profiles_display_name_uq")
    val avatarEmoji = text("avatar_emoji")
    /** Nullable — NULL means "use theme default" on the client. */
    val avatarBackgroundColor = text("avatar_background_color").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    /**
     * Monotonic per-profile sequence assigned at insert time by Postgres
     * (see V43). Used by the application layer to recognise which users
     * sit inside the founding-member window — the `BIGSERIAL` provides
     * race-free ordering across concurrent first-contact inserts.
     */
    val seq = long("seq").uniqueIndex("profiles_seq_uq")
    /**
     * Client-generated UUID per app installation (V49). Tagged on every
     * `/v1/me` request from the `X-Install-Id` header. Nullable for legacy
     * rows that existed before V49 landed; steady-state non-null for any
     * profile that's seen one authed `/v1/me` from a current client.
     */
    val installId = uuid("install_id").nullable()
    override val primaryKey = PrimaryKey(userId)
}

/**
 * One row per (user, equipped product). Row's mere presence means the
 * user has the product equipped — unequipping deletes the row. See
 * `V2__equipment.sql` for the schema authority.
 */
object EquipmentTable : Table("equipment") {
    val userId = uuid("user_id")
    val productId = text("product_id")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, productId)
}

/**
 * One row per (user, owned product). See `V3__inventory.sql`.
 */
object InventoryTable : Table("inventory") {
    val userId = uuid("user_id")
    val productId = text("product_id")
    val costChipsAtPurchase = long("cost_chips_at_purchase")
    val purchasedAt = timestamp("purchased_at")
    // 'purchased' | 'earned'. CHECK constraint enforced at the DB level
    // (see V13__inventory_acquisition_source.sql). The string-typed
    // column maps to [com.dangerfield.cards.server.domain.AcquisitionSource]
    // on read; the repo writes the lowercase enum name back.
    val acquisitionSource = text("acquisition_source").default("purchased")
    override val primaryKey = PrimaryKey(userId, productId)
}

/**
 * Shop catalog. One row per product (chip pack or chip offer). See
 * `V5__products.sql` for the schema authority + seeding strategy.
 *
 * Localized strings (`title_by_locale`, `subtitle_by_locale`, `badge_by_locale`,
 * `description_by_locale`) are JSONB on the DB side; we read them as text and
 * parse with kotlinx-serialization so the Exposed surface stays small (no
 * extra JSONB column-type dependency). Same trick for the `platforms` TEXT[] —
 * we read it as a Postgres array via raw SQL in the repository.
 */
object ProductsTable : Table("products") {
    val id = text("id")
    val kind = text("kind")
    val sortOrder = integer("sort_order")
    val iconEmoji = text("icon_emoji")
    val featured = bool("featured")
    val availableUntilEpochMs = long("available_until_epoch_ms").nullable()

    // JSONB columns surfaced as text — JSON parsing happens in the repo.
    val titleByLocale = text("title_by_locale")
    val subtitleByLocale = text("subtitle_by_locale")
    val badgeByLocale = text("badge_by_locale").nullable()
    val descriptionByLocale = text("description_by_locale").nullable()

    // ChipPack-only.
    val grantsChips = long("grants_chips").nullable()
    val iosSku = text("ios_sku").nullable()
    val iosFallbackPrice = text("ios_fallback_price").nullable()
    val androidSku = text("android_sku").nullable()
    val androidFallbackPrice = text("android_fallback_price").nullable()

    // ChipOffer-only.
    val grantsKey = text("grants_key").nullable()
    val costChips = long("cost_chips").nullable()
    val unlockLevel = integer("unlock_level").nullable()

    val unlockOnly = bool("unlock_only").default(false)
    val isEquippable = bool("is_equippable").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Server-authoritative chip balance, one row per user. Lazy-created on
 * first `GET /v1/me/wallet` with the starter grant. See `V6__wallets.sql`.
 */
object WalletsTable : Table("wallets") {
    val userId = uuid("user_id")
    val balance = long("balance")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Append-only ledger of chip movements. `(user_id, idempotency_key)`
 * is the dedup boundary — retried sync requests collapse to a single
 * row. See `V6__wallets.sql`.
 */
object WalletEventsTable : Table("wallet_events") {
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val delta = long("delta")
    val reason = text("reason")
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(userId, idempotencyKey)
}

/**
 * Server-authoritative XP total, one row per user. Lazy-created on first
 * progression contact. `total_xp` is summed from [XpEventsTable]; `level`
 * is derived client-side from the curve, never stored. See
 * `V52__xp_progression.sql`.
 */
object UserProgressionTable : Table("user_progression") {
    val userId = uuid("user_id")
    val totalXp = long("total_xp")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Append-only ledger of XP awards. `(user_id, idempotency_key)` is the
 * dedup boundary — retried/reinstalled syncs collapse to a single row.
 * See `V52__xp_progression.sql`.
 */
object XpEventsTable : Table("xp_events") {
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val deltaXp = long("delta_xp")
    // `source` is named `eventSource` because `source` collides with an
    // Exposed ColumnSet member; the DB column is still `source`.
    val eventSource = text("source")
    val mode = text("mode")
    val handId = text("hand_id").nullable()
    // Whether an XP boost doubled this award — round-trips to the client so the
    // recent-XP feed can flag boosted rows after a reinstall. Defaulted so
    // pre-V58 rows and older clients read false.
    val wasBoosted = bool("was_boosted").default(false)
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(userId, idempotencyKey)
}

/**
 * Server-authoritative earned-achievement set. One row per (user,
 * achievement); first-write-wins on `earned_at`. The criteria engine +
 * progress counters stay client-local — only the earned set syncs. See
 * `V53__achievements_earned.sql`.
 */
object AchievementsEarnedTable : Table("achievements_earned") {
    val userId = uuid("user_id")
    val achievementId = text("achievement_id")
    val earnedAt = timestamp("earned_at")
    override val primaryKey = PrimaryKey(userId, achievementId)
}

/**
 * Append-only ledger of finished hands witnessed by the authoritative
 * server loop. One row per (user, finished hand); `(user_id,
 * idempotency_key)` is the dedup boundary so a replayed hand-completion
 * collapses to a single row. The per-user count gates multiplayer
 * achievement grants. See `V56__hand_finished_counts.sql`.
 */
object HandFinishedEventsTable : Table("hand_finished_events") {
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val handSessionId = uuid("hand_session_id")
    val handNumber = integer("hand_number")
    val finishedAt = timestamp("finished_at")
    val bustsDealt = integer("busts_dealt").default(0)
    val wonByFold = bool("won_by_fold").default(false)
    override val primaryKey = PrimaryKey(userId, idempotencyKey)
}

/**
 * Snapshot of the live `GameSession` state for a room. One row per active
 * session, overwritten on every state mutation inside the per-session
 * mutex. Hydrated on registry lookup when in-memory has no entry for the
 * code (server restart path). See `V48__room_sessions.sql`.
 */
object RoomSessionsTable : Table("room_sessions") {
    val sessionId = uuid("session_id")
    val roomCode = text("room_code").uniqueIndex("room_sessions_room_code_uq")
    val stateJsonb = jsonb("state_jsonb")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(sessionId)
}

/**
 * Durable room registry. One row per live room; GC'd (via `room_members`
 * cascade + an explicit room delete) when the last member leaves.
 * InMemoryRoomService write-through + hydration source of truth. See
 * `V65__rooms_and_members.sql`.
 */
object RoomsTable : Table("rooms") {
    val code = text("code")
    val hostUserId = uuid("host_user_id")
    val status = text("status")
    val buyIn = long("buy_in")
    val maxSeats = integer("max_seats")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(code)
}

/**
 * Seats in a room. One row per (room, member); a NULL `botPersonality` marks a
 * human, otherwise the `bot*` columns capture the backend bot seat. `(roomCode,
 * seatIndex)` is uniquely constrained so the seating chart can't double-book.
 * See `V65__rooms_and_members.sql`.
 */
object RoomMembersTable : Table("room_members") {
    val roomCode = text("room_code").references(RoomsTable.code)
    val userId = uuid("user_id")
    val seatIndex = integer("seat_index")
    val displayName = text("display_name")
    val joinedAt = timestamp("joined_at")
    val disconnectedAt = timestamp("disconnected_at").nullable()
    val avatarEmoji = text("avatar_emoji")
    val avatarBackgroundColor = text("avatar_background_color").nullable()
    val botPersonality = text("bot_personality").nullable()
    val botDifficulty = text("bot_difficulty").nullable()
    val botRevealed = bool("bot_revealed").nullable()
    override val primaryKey = PrimaryKey(roomCode, userId)
}

/**
 * Friend graph. One row per *unordered pair* of users — `userA` is always the
 * lexicographically smaller UUID, `userB` the larger, so a relation is unique
 * regardless of direction. `state` is the pair lifecycle (requested / accepted
 * / blocked) and `actedBy` carries the direction the canonical pair can't (the
 * sender of a request, the blocker of a block). See `V61__friend_relations.sql`.
 */
object FriendRelationsTable : Table("friend_relations") {
    val userA = uuid("user_a")
    val userB = uuid("user_b")
    val state = text("state")
    val actedBy = uuid("acted_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userA, userB)
}

/**
 * Recently-played-with shelf. One *directed* row per (viewer, opponent) —
 * `userId`'s view of `opponentId`. Both directions are written when a hand
 * finishes, so a user's shelf reads as `WHERE user_id = me ORDER BY
 * last_played_at DESC`. `lastPlayedAt` is bumped on every shared hand, keeping
 * one row per pair with recency ordering implicit. See
 * `V62__recently_played_with.sql`.
 */
object RecentlyPlayedWithTable : Table("recently_played_with") {
    val userId = uuid("user_id")
    val opponentId = uuid("opponent_id")
    val lastPlayedAt = timestamp("last_played_at")
    override val primaryKey = PrimaryKey(userId, opponentId)
}

/**
 * Per-user in-app messages. Authored by admins, delivered as either a
 * dialog (modal pop on foreground) or an inbox row (passive entry in
 * the Notifications screen). Acked exactly once; expiry filters out
 * stale notices before delivery. See `V8__user_messages.sql` +
 * `V9__user_messages_kind_and_expiry.sql`.
 */
object UserMessagesTable : Table("user_messages") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val kind = text("kind")
    val emoji = text("emoji").nullable()
    val title = text("title")
    val body = text("body")
    val deepLink = text("deep_link").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at").nullable()
    val ackedAt = timestamp("acked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

