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

// Protocol="hls" (not "http"): a progressive-http transcode is generated on the fly with no
// Content-Length, so ExoPlayer can never seek in it — the scrub bar sits permanently disabled.
// HLS transcodes are segmented with known durations in the playlist, so seeking works even
// while ffmpeg is still transcoding ahead of the playhead.
data class TranscodingProfile(
    val Type: String = "Video",
    val Container: String = "ts",
    val VideoCodec: String = "h264",
    val AudioCodec: String = "aac",
    val Context: String = "Streaming",
    val Protocol: String = "hls",
)

// Condition/Property/Value/IsRequired is the shape Jellyfin's server evaluates against each
// MediaSource's actual stream properties (Width, Height, VideoBitrate, ...) both to decide
// whether Direct Play is allowed and to size the transcode output. A bitrate cap alone only
// tells the encoder a target bitrate — without a Width/Height condition here, Jellyfin re-encodes
// at the *source's original resolution* squeezed into that bitrate (blocky 4K, not actually 720p).
data class ProfileCondition(
    val Condition: String = "LessThanEqual",
    val Property: String,
    val Value: String,
    val IsRequired: Boolean = false,
)

data class CodecProfile(
    val Type: String = "Video",
    val Codec: String = "h264,hevc,vp9,av1,mpeg4,mpeg2video",
    val Conditions: List<ProfileCondition>,
)

data class DeviceProfile(
    val MaxStreamingBitrate: Int = 120_000_000,
    // MaxStreamingBitrate alone only caps the transcode *output* target — it's MaxStaticBitrate
    // that Jellyfin compares the source's actual bitrate against to decide whether Direct Play is
    // even allowed. Without it, a 4K source direct-plays regardless of the app's quality setting.
    val MaxStaticBitrate: Int? = null,
    val DirectPlayProfiles: List<DirectPlayProfile> = listOf(DirectPlayProfile()),
    val TranscodingProfiles: List<TranscodingProfile> = listOf(TranscodingProfile()),
    val CodecProfiles: List<CodecProfile> = emptyList(),
)

data class PlaybackInfoRequest(
    val DeviceProfile: DeviceProfile = DeviceProfile(),
    val MaxStreamingBitrate: Int? = null,
)

data class PlaybackMediaSource(
    val Id: String,
    val Container: String? = null,
    val SupportsDirectPlay: Boolean = false,
    val SupportsDirectStream: Boolean = false,
    val TranscodingUrl: String? = null,
    val TranscodeReasons: List<String>? = null,
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
        "$baseUrl/Items/$itemId/Images/Backdrop/$index?quality=96&maxWidth=1920"

    private val version get() = BuildConfig.VERSION_NAME

    fun authHeader(token: String): String =
        """MediaBrowser Client="JellyJar", Device="Android", DeviceId="jellyjar-android", Version="$version", Token="$token""""

    fun unauthHeader(): String =
        """MediaBrowser Client="JellyJar", Device="Android", DeviceId="jellyjar-android", Version="$version""""

    // Without a container extension in the path, Jellyfin can't guarantee a byte-range-capable,
    // Content-Length response for /stream — it may fall back to chunked transfer, which
    // ExoPlayer reports as non-seekable (the scrub bar won't drag at all). Passing the actual
    // source container + MediaSourceId gets a proper static file response that is seekable.
    fun streamUrl(
        baseUrl: String,
        itemId: String,
        token: String,
        container: String? = null,
        mediaSourceId: String? = null,
    ): String {
        val ext = container?.let { ".$it" } ?: ""
        val mediaSourceParam = mediaSourceId?.let { "&mediaSourceId=$it" } ?: ""
        return "$baseUrl/Videos/$itemId/stream$ext?static=true$mediaSourceParam&api_key=$token"
    }

    fun trickplayTileUrl(baseUrl: String, itemId: String, width: Int, tileIndex: Int, token: String): String =
        "$baseUrl/Videos/$itemId/Trickplay/$width/$tileIndex.jpg?api_key=$token"
}
