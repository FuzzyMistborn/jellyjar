package com.fuzzymistborn.jellyjar.api

import com.fuzzymistborn.jellyjar.BuildConfig
import com.fuzzymistborn.jellyjar.model.IntroSkipperSegment
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.model.JellyfinLibrary
import com.fuzzymistborn.jellyjar.model.MediaSegmentsResponse
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

// ─── Playback info / device profile negotiation ───────────────────────────────
// Lets Jellyfin decide direct-play vs transcode per MediaSource instead of the client always
// assuming direct-play. Needed because stock ExoPlayer has no DTS/DTS-HD MA or TrueHD decoder —
// omitting those from DirectPlayProfiles' AudioCodec list makes the server transcode just the
// audio (remux) for those files while everything else still direct-plays.

data class DirectPlayProfile(
    val Type: String = "Video",
    val Container: String = "mp4,mkv,avi,mov,webm,m4v,ts,m2ts",
    val VideoCodec: String = "h264,hevc,vp9,av1,mpeg4,mpeg2video",
    val AudioCodec: String = "aac,mp3,ac3,eac3,flac,alac,opus,vorbis,pcm_s16le,pcm_s24le",
)

data class TranscodingProfile(
    val Type: String = "Video",
    val Container: String = "mp4",
    val VideoCodec: String = "h264",
    val AudioCodec: String = "aac",
    val Context: String = "Streaming",
    val Protocol: String = "http",
)

data class DeviceProfile(
    val MaxStreamingBitrate: Int = 120_000_000,
    val DirectPlayProfiles: List<DirectPlayProfile> = listOf(DirectPlayProfile()),
    val TranscodingProfiles: List<TranscodingProfile> = listOf(TranscodingProfile()),
)

data class PlaybackInfoRequest(
    val DeviceProfile: DeviceProfile = DeviceProfile(),
)

data class PlaybackMediaSource(
    val Id: String,
    val SupportsDirectPlay: Boolean = false,
    val SupportsDirectStream: Boolean = false,
    val TranscodingUrl: String? = null,
)

data class PlaybackInfoResponse(
    val MediaSources: List<PlaybackMediaSource> = emptyList(),
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
        @Query("Fields") fields: String = "Overview,MediaSources,BackdropImageTags,ImageTags,UserData,Genres,Trickplay",
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 50,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("Filters") filters: String? = null,
        @Query("Genres") genres: String? = null,
    ): ItemsResponse

    @GET("Genres")
    suspend fun getGenres(
        @Header("Authorization") authHeader: String,
        @Query("ParentId") parentId: String? = null,
        @Query("UserId") userId: String? = null,
        @Query("SortBy") sortBy: String = "SortName",
    ): ItemsResponse

    // Jellyfin 10.9+ native media segments (intro/outro markers)
    @GET("MediaSegments/{itemId}")
    suspend fun getMediaSegments(
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
    ): MediaSegmentsResponse

    // Intro Skipper plugin fallback — returns {"Introduction": {...}, "Credits": {...}}
    @GET("Episode/{itemId}/IntroSkipperSegments")
    suspend fun getIntroSkipperSegments(
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
    ): Map<String, IntroSkipperSegment>

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

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun getPlaybackInfo(
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
        @Query("UserId") userId: String,
        @Body body: PlaybackInfoRequest = PlaybackInfoRequest(),
    ): PlaybackInfoResponse

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Header("Authorization") authHeader: String,
        @Query("Fields") fields: String = "Overview,MediaSources,BackdropImageTags,ImageTags,UserData,Genres,Trickplay",
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

    fun trickplayTileUrl(baseUrl: String, itemId: String, width: Int, tileIndex: Int, token: String): String =
        "$baseUrl/Videos/$itemId/Trickplay/$width/$tileIndex.jpg?api_key=$token"
}
