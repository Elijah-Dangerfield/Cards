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
 * One row per redeemed store purchase (IAP). `(store, order_id)` is the
 * dedup boundary that makes `POST /v1/billing/redeem` idempotent — a
 * retried redemption collapses to a single grant. Written in the same
 * transaction as the wallet credit. See `V81__billing_transactions.sql`.
 */
object BillingTransactionsTable : Table("billing_transactions") {
    val id = long("id").autoIncrement()
    val store = text("store")
    val orderId = text("order_id")
    val userId = uuid("user_id")
    val productId = text("product_id")
    val grantedChips = long("granted_chips")
    val environment = text("environment")
    val redeemedAt = timestamp("redeemed_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("billing_transactions_store_order_uq", store, orderId)
    }
}

/**
 * The redeem-attempt disposition log — one evolving row per store transaction,
 * upserted on every attempt. The source of truth for support and the Grafana
 * billing-health panel; distinct from [BillingTransactionsTable] (the atomic
 * grant record). See `V88__billing_events.sql`.
 */
object BillingEventsTable : Table("billing_events") {
    val id = long("id").autoIncrement()
    val store = text("store")
    val transactionId = text("transaction_id")
    val callerUserId = uuid("caller_user_id")
    val receiptOwner = uuid("receipt_owner").nullable()
    val productId = text("product_id")
    val reason = text("reason").nullable()
    val finalAction = text("final_action")
    val attemptCount = integer("attempt_count")
    val firstSeenAt = timestamp("first_seen_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("billing_events_store_txn_uq", store, transactionId)
    }
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
 * Server-authoritative play-style aggregate, one row per user. Rolling sums
 * of per-hand counters flushed from the client; the four axes (Tight / Aggro
 * / Bluff / Patient) are computed from these at read time, never stored, so
 * the formula stays tunable. Also the public read surface for the Opponent
 * Style Reader. See `V69__play_style.sql`.
 */
object UserPlayStyleAggregateTable : Table("user_play_style_aggregate") {
    val userId = uuid("user_id")
    val handsDealt = long("hands_dealt")
    val handsDealtNonBlind = long("hands_dealt_non_blind")
    val vpipCount = long("vpip_count")
    val pfrCount = long("pfr_count")
    val preflopFoldCount = long("preflop_fold_count")
    val aggressiveActionCount = long("aggressive_action_count")
    val callActionCount = long("call_action_count")
    val showdownCount = long("showdown_count")
    val showdownBluffCount = long("showdown_bluff_count")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Append-only ledger of per-hand play-style contributions. `(user_id,
 * idempotency_key)` is the dedup boundary — a retried/reinstalled flush
 * collapses to a single row, so the aggregate never double-counts a hand.
 * See `V69__play_style.sql`.
 */
object PlayStyleEventsTable : Table("play_style_events") {
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val mode = text("mode")
    val inBlind = bool("in_blind")
    val vpip = bool("vpip")
    val pfr = bool("pfr")
    val preflopFold = bool("preflop_fold")
    val aggressiveActionCount = integer("aggressive_action_count")
    val callActionCount = integer("call_action_count")
    val wentToShowdown = bool("went_to_showdown")
    val showdownBluff = bool("showdown_bluff")
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(userId, idempotencyKey)
}

/**
 * Server-authoritative player stats, one row per user. Cumulative hand
 * counters + the no-bust streak (current + best) + a per-bot win map; the
 * authoritative read for the stats screen and the source achievements read
 * their progress from. See `V72__player_stats.sql`.
 */
object UserPlayerStatsTable : Table("user_player_stats") {
    val userId = uuid("user_id")
    val handsPlayed = long("hands_played")
    val handsWon = long("hands_won")
    val handsFolded = long("hands_folded")
    val handsLostAtShowdown = long("hands_lost_at_showdown")
    val botHandsPlayed = long("bot_hands_played")
    val currentNoBustStreak = long("current_no_bust_streak")
    val bestNoBustStreak = long("best_no_bust_streak")
    val perBotWins = jsonb("per_bot_wins")
    /** Materialized achievement-counter projection: keyed `name -> value` (see V73). */
    val achievementCounters = jsonb("achievement_counters")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Append-only ledger of per-hand stat contributions. `(user_id,
 * idempotency_key)` is the dedup boundary — a retried/reinstalled flush
 * collapses to a single row, so the aggregate never double-counts a hand.
 * See `V72__player_stats.sql`.
 */
object PlayerStatEventsTable : Table("player_stat_events") {
    val userId = uuid("user_id")
    val idempotencyKey = text("idempotency_key")
    val mode = text("mode")
    val won = bool("won")
    val folded = bool("folded")
    val lostAtShowdown = bool("lost_at_showdown")
    val vsBot = bool("vs_bot")
    val beatenBotId = text("beaten_bot_id").nullable()
    val noBustStreak = long("no_bust_streak")
    // Enriched raw facts (V73) — the complete hand record the counter fold reads.
    // Nullable/defaulted so pre-V73 rows and older clients keep applying.
    val busted = bool("busted")
    val startStack = long("start_stack")
    val endStack = long("end_stack")
    val bigBlind = long("big_blind")
    val potTotal = long("pot_total")
    val wasAllIn = bool("was_all_in")
    val wonByFold = bool("won_by_fold")
    val bustsDealt = integer("busts_dealt")
    val foldedWouldHaveLost = bool("folded_would_have_lost")
    val handStrengthShown = text("hand_strength_shown").nullable()
    val botDifficulty = text("bot_difficulty").nullable()
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
    // `{playerId -> stack}` retained even after a player busts + is dropped, so
    // the crash-recovery sweep cashes a busted-and-dropped player out their real
    // 0 instead of refunding their escrow (MP-13). Defaulted so pre-V74 rows read
    // as an empty map. See V74.
    val lastKnownStacksJsonb = jsonb("last_known_stacks_jsonb").default("{}")
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
    // 'private' | 'open' | 'public' (V66). Defaulted at the DB level so pre-V66
    // rows + Exposed inserts that omit it read as 'private'.
    val visibility = text("visibility")
    // Host-chosen table cosmetics (SHOP-3): the felt + card-back catalog product
    // ids the host had equipped at create time, applied table-wide. Nullable —
    // null means the host had nothing equipped in that slot. See V82.
    val feltProductId = text("felt_product_id").nullable()
    val cardBackProductId = text("card_back_product_id").nullable()
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
 * Durable interlock between an MP table seat's in-RAM stack and the chip wallet
 * (V67, salvaged from the chip-economy branch). One row per sit-down; the actual
 * chips move only in `wallet_events`. Brought in DORMANT — the sit-down /
 * cash-out wiring is Phase 4. See `V67__table_sessions.sql`.
 */
object TableSessionsTable : Table("table_sessions") {
    val sessionId = uuid("session_id")
    val userId = uuid("user_id")
    val roomCode = text("room_code")
    val stakeTier = text("stake_tier")
    val buyIn = long("buy_in")
    val rebuyCount = integer("rebuy_count").default(0)
    val status = text("status")
    val openedAt = timestamp("opened_at")
    val closedAt = timestamp("closed_at").nullable()
    // Disclosed-bot subsidy (V68): `subsidized` tags a public lone-human-vs-bots
    // session; `subsidyGranted` is the net house-funded win recorded at cash-out,
    // summed for the per-user rolling-24h cap.
    val subsidized = bool("subsidized").default(false)
    val subsidyGranted = long("subsidy_granted").default(0)
    override val primaryKey = PrimaryKey(sessionId)
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
 * Player reports — the trust-and-safety table behind the in-app "Report a
 * player" action (MOD-1). One row per report; a reporter may file more than
 * one against the same user, so there's no unique constraint on the pair. No
 * auto-ban in V1 — a human reads these later via the reported-user index. See
 * `V85__player_reports.sql`.
 */
object PlayerReportsTable : Table("player_reports") {
    val id = long("id").autoIncrement()
    val reporterUserId = uuid("reporter_user_id")
    val reportedUserId = uuid("reported_user_id")
    val roomCode = text("room_code").nullable()
    val reason = text("reason").nullable()
    val reasonCategories = text("reason_categories").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
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

/**
 * DB-backed remote-config base values. One row per `ConfiguredValue.path`
 * the server overrides; `value_jsonb` is the value served when no targeting
 * rule matches. Backs [com.dangerfield.cards.server.data.PostgresAppConfigSource].
 * See `V75__app_config.sql`.
 */
object AppConfigValuesTable : Table("app_config_values") {
    val path = text("path")
    val valueJsonb = jsonb("value_jsonb")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(path)
}

/**
 * Per-flag targeting rules. One row per rule; evaluated in ascending `priority`
 * with first-match-wins, falling back to the [AppConfigValuesTable] base value.
 * `conditions_jsonb` is the serialized predicate (platform / version-code range
 * / country / locale / user-id allow-deny / rollout bucket). FK-cascaded off the
 * flag's base value so deleting a flag drops its rules. See `V76__app_config_targeting.sql`.
 */
object AppConfigRulesTable : Table("app_config_rules") {
    val id = uuid("id")
    val flagPath = text("flag_path")
    val priority = integer("priority")
    val valueJsonb = jsonb("value_jsonb")
    val conditionsJsonb = jsonb("conditions_jsonb")
    val enabled = bool("enabled")
    val description = text("description").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Append-only audit log of every config mutation made through the admin API.
 * `before_jsonb` / `after_jsonb` capture the changed row's state for diffing.
 * See `V76__app_config_targeting.sql`.
 */
object AppConfigAuditTable : Table("app_config_audit") {
    val id = uuid("id")
    val at = timestamp("at")
    val actor = text("actor")
    val action = text("action")
    val flagPath = text("flag_path").nullable()
    val beforeJsonb = jsonb("before_jsonb").nullable()
    val afterJsonb = jsonb("after_jsonb").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * The in-code config registry captured per app version: every `ConfiguredValue`
 * the build ships with, its type, in-code default, and (for enum-like flags)
 * allowed values. Uploaded by CI at release time (one row per flag per
 * `version_code`); the admin tool reads it to answer "what did 1.0.1 ship with"
 * and to show the baseline a remote override would replace. See
 * `V77__app_config_manifest.sql`.
 */
object AppConfigManifestTable : Table("app_config_manifest") {
    val versionCode = integer("version_code")
    val appVersion = text("app_version").nullable()
    val path = text("path")
    val type = text("type")
    val defaultJsonb = jsonb("default_jsonb")
    val description = text("description").nullable()
    val allowedValuesJsonb = jsonb("allowed_values_jsonb").nullable()
    val capturedAt = timestamp("captured_at")
    override val primaryKey = PrimaryKey(versionCode, path)
}

