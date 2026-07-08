package com.fuzzymistborn.jellyjar.api

import com.fuzzymistborn.jellyjar.BuildConfig
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.model.JellyfinLibrary
import retrofit2.http.*

// ─── Auth ─────────────────────────────────────────────────────────────────────

data class AuthRequest(
    val Username: String,
    val Pw: String,
)

data class AuthResponse(
    val AccessToken: String,
    val ServerId: String,
    val User: JellyfinUser,
)

data class JellyfinUser(
    val Id: String,
    val Name: String,
)

// ─── Playback session reporting ───────────────────────────────────────────────

data class PlaybackStartRequest(
    val ItemId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean = false,
    val IsMuted: Boolean = false,
    val PlayMethod: String = "DirectStream",
    val MediaSourceId: String? = null,
)

data class PlaybackProgressRequest(
    val ItemId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean,
    val IsMuted: Boolean = false,
    val PlayMethod: String = "DirectStream",
    val MediaSourceId: String? = null,
)

data class PlaybackStopRequest(
    val ItemId: String,
    val PositionTicks: Long,
    val MediaSourceId: String? = null,
)

// ─── Item list wrappers ───────────────────────────────────────────────────────

data class ItemsResponse(
    @com.google.gson.annotations.SerializedName("Items") val Items: List<JellyfinItem>,
    @com.google.gson.annotations.SerializedName("TotalRecordCount") val TotalRecordCount: Int,
)

data class LibraryResponse(
    @com.google.gson.annotations.SerializedName("Items") val Items: List<JellyfinLibrary>,
)

// ─── API interface ────────────────────────────────────────────────────────────

interface JellyfinApiService {

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(
        @Header("Authorization") authHeader: String,
        @Body body: AuthRequest,
    ): AuthResponse

    @GET("Users/{userId}/Views")
    suspend fun getLibraries(
        @Path("userId") userId: String,
        @Header("Authorization") authHeader: String,
    ): LibraryResponse

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Path("userId") userId: String,
        @Header("Authorization") authHeader: String,
        @Query("ParentId") parentId: String? = null,
        @Query("IncludeItemTypes") types: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "Overview,MediaSources,BackdropImageTags,ImageTags,UserData",
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 50,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("Filters") filters: String? = null,
    ): ItemsResponse

    @POST("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
    )

    @DELETE("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
    )

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(
        @Header("Authorization") authHeader: String,
        @Body body: PlaybackStartRequest,
    )

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(
        @Header("Authorization") authHeader: String,
        @Body body: PlaybackProgressRequest,
    )

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(
        @Header("Authorization") authHeader: String,
        @Body body: PlaybackStopRequest,
    )

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
        @Query("Fields") fields: String = "Overview,MediaSources,BackdropImageTags,ImageTags,UserData",
    ): JellyfinItem
}

// ─── Image URL helpers ────────────────────────────────────────────────────────

object JellyfinImageHelper {
    fun primaryImageUrl(baseUrl: String, itemId: String, maxWidth: Int = 400): String =
        "$baseUrl/Items/$itemId/Images/Primary?maxWidth=$maxWidth&quality=90"

    fun backdropImageUrl(baseUrl: String, itemId: String, index: Int = 0): String =
        "$baseUrl/Items/$itemId/Images/Backdrop/$index?quality=85"

    fun logoImageUrl(baseUrl: String, itemId: String): String =
        "$baseUrl/Items/$itemId/Images/Logo?quality=90"

    private val version get() = BuildConfig.VERSION_NAME

    fun authHeader(token: String): String =
        """MediaBrowser Client="JellyJar", Device="Android", DeviceId="jellyjar-android", Version="$version", Token="$token""""

    fun unauthHeader(): String =
        """MediaBrowser Client="JellyJar", Device="Android", DeviceId="jellyjar-android", Version="$version""""

    fun streamUrl(baseUrl: String, itemId: String, token: String): String =
        "$baseUrl/Videos/$itemId/stream?static=true&api_key=$token"
}
