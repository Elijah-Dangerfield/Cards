package com.dangerfield.cards.libraries.social.impl

/**
 * In-memory [SocialApi] for the repository tests. Each call can be stubbed to
 * succeed (with the configured data) or to throw a configured error.
 */
internal class FakeSocialApi(
    var recentIds: List<String> = emptyList(),
    var profiles: List<PublicProfileDto> = emptyList(),
    var recentError: Throwable? = null,
    var profilesError: Throwable? = null,
    var sendResult: Result<FriendRequestResultDto> = Result.success(FriendRequestResultDto("requested")),
    var incomingRequestIds: List<String> = emptyList(),
    var incomingError: Throwable? = null,
    var acceptResult: Result<FriendRequestResultDto> = Result.success(FriendRequestResultDto("ok")),
    var declineResult: Result<FriendRequestResultDto> = Result.success(FriendRequestResultDto("ok")),
) : SocialApi {

    var lastLimit: Int? = null
        private set
    var lastSentUserId: String? = null
        private set
    var lastAcceptedUserId: String? = null
        private set
    var lastDeclinedUserId: String? = null
        private set
    var resolveCallCount: Int = 0
        private set

    override suspend fun recentOpponentIds(limit: Int): List<String> {
        lastLimit = limit
        recentError?.let { throw it }
        return recentIds
    }

    override suspend fun resolveProfiles(ids: List<String>): List<PublicProfileDto> {
        resolveCallCount++
        profilesError?.let { throw it }
        return profiles.filter { it.id in ids }
    }

    override suspend fun sendFriendRequest(userId: String): FriendRequestResultDto {
        lastSentUserId = userId
        return sendResult.getOrThrow()
    }

    override suspend fun incomingFriendRequestIds(): List<String> {
        incomingError?.let { throw it }
        return incomingRequestIds
    }

    override suspend fun acceptFriendRequest(userId: String): FriendRequestResultDto {
        lastAcceptedUserId = userId
        return acceptResult.getOrThrow()
    }

    override suspend fun declineFriendRequest(userId: String): FriendRequestResultDto {
        lastDeclinedUserId = userId
        return declineResult.getOrThrow()
    }
}

internal fun publicProfile(id: String): PublicProfileDto = PublicProfileDto(
    id = id,
    displayName = "name-$id",
    avatarEmoji = "🦊",
    avatarBackgroundColor = null,
)
