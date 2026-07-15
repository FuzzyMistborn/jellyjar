package com.fuzzymistborn.jellyjar.model

import com.google.gson.annotations.SerializedName

// ─── Jellyfin API models ──────────────────────────────────────────────────────

data class JellyfinItem(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String,
    @SerializedName("Type") val type: String,
    @SerializedName("Overview") val overview: String?,
    @SerializedName("ProductionYear") val year: Int?,
    @SerializedName("CommunityRating") val communityRating: Float?,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long?,
    @SerializedName("SeriesName") val seriesName: String?,
    @SerializedName("SeasonName") val seasonName: String?,
    @SerializedName("IndexNumber") val indexNumber: Int?,
    @SerializedName("ParentIndexNumber") val parentIndexNumber: Int?,
    @SerializedName("MediaSources") val mediaSources: List<MediaSource>?,
    @SerializedName("ImageTags") val imageTags: Map<String, String>?,
    @SerializedName("BackdropImageTags") val backdropImageTags: List<String>?,
    @SerializedName("UserData") val userData: UserData?,
    @SerializedName("SeriesId") val seriesId: String? = null,
    @SerializedName("SeasonId") val seasonId: String? = null,
    @SerializedName("Genres") val genres: List<String>? = null,
    // mediaSourceId → (width → tile info); present when the server has generated trickplay images
    @SerializedName("Trickplay") val trickplay: Map<String, Map<String, TrickplayInfo>>? = null,
) {
    val runtimeMinutes: Int?
        get() = runTimeTicks?.let { (it / 600_000_000).toInt() }

    val displayTitle: String
        get() = when {
            type == "Episode" && seriesName != null ->
                "$seriesName · S${parentIndexNumber?.toString()?.padStart(2, '0')}E${indexNumber?.toString()?.padStart(2, '0')} · $name"
            else -> name
        }
}

data class MediaSource(
    @SerializedName("Id") val id: String,
    @SerializedName("Path") val path: String?,
    @SerializedName("Size") val size: Long?,
    @SerializedName("Container") val container: String?,
    @SerializedName("VideoStreams") val videoStreams: List<VideoStream>?,
    @SerializedName("AudioStreams") val audioStreams: List<AudioStream>?,
)

data class VideoStream(
    @SerializedName("Codec") val codec: String?,
    @SerializedName("Width") val width: Int?,
    @SerializedName("Height") val height: Int?,
    @SerializedName("BitRate") val bitRate: Long?,
)

data class AudioStream(
    @SerializedName("Codec") val codec: String?,
    @SerializedName("Channels") val channels: Int?,
    @SerializedName("Language") val language: String?,
)

data class TrickplayInfo(
    @SerializedName("Width") val width: Int,
    @SerializedName("Height") val height: Int,
    @SerializedName("TileWidth") val tileWidth: Int,     // thumbnails per tile row
    @SerializedName("TileHeight") val tileHeight: Int,   // thumbnails per tile column
    @SerializedName("ThumbnailCount") val thumbnailCount: Int,
    @SerializedName("Interval") val interval: Int,       // ms between thumbnails
)

// ─── Skip segments (intro/credits) ────────────────────────────────────────────

// Jellyfin 10.9+ native media segments API
data class MediaSegment(
    @SerializedName("Type") val type: String?,           // Intro | Outro | Recap | Preview | Commercial
    @SerializedName("StartTicks") val startTicks: Long?,
    @SerializedName("EndTicks") val endTicks: Long?,
)

data class MediaSegmentsResponse(
    @SerializedName("Items") val items: List<MediaSegment>?,
)

// Intro Skipper plugin fallback; field names differ between plugin versions
data class IntroSkipperSegment(
    @SerializedName("IntroStart") val introStart: Double?,
    @SerializedName("IntroEnd") val introEnd: Double?,
    @SerializedName("Start") val start: Double?,
    @SerializedName("End") val end: Double?,
    @SerializedName("Valid") val valid: Boolean?,
)

// Normalized form used by the player and persisted (as JSON) on downloads
data class SkipSegment(
    val type: String,       // "Intro" or "Outro"
    val startMs: Long,
    val endMs: Long,
) {
    val label: String get() = if (type == "Outro") "Skip Credits" else "Skip Intro"
}

data class UserData(
    @SerializedName("PlaybackPositionTicks") val playbackPositionTicks: Long?,
    @SerializedName("Played") val played: Boolean,
    @SerializedName("PlayCount") val playCount: Int,
)

data class JellyfinLibrary(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("CollectionType") val collectionType: String?,
)

// ─── Transcode / shim models ──────────────────────────────────────────────────

data class TranscodeRequest(
    val source_path: String,
    val preset: String,
    val output_filename: String?,
    val display_name: String? = null,
)

data class TranscodeJob(
    val job_id: String,
    val status: String,             // queued | running | complete | failed
    val progress: Float?,
    val output_path: String?,
    val error: String?,
    val created_at: String,
    val updated_at: String,
)

data class PresetConfigDto(
    val video_bitrate: String,
    val audio_bitrate: String,
    val scale: String,
    val crf: String,
)

// ─── Local download model ─────────────────────────────────────────────────────

enum class DownloadStatus {
    QUEUED, TRANSCODING, DOWNLOADING, COMPLETE, FAILED
}

data class DownloadItem(
    val jellyfinId: String,
    val title: String,
    val localPath: String,
    val status: DownloadStatus,
    val progress: Float,
    val sizeBytes: Long,
    val preset: String,
    val addedAt: Long,
    val thumbnailPath: String?,
)

// ─── Settings model ───────────────────────────────────────────────────────────

enum class SortOrder {
    DEFAULT, ALPHABETICAL, YEAR_ASC, YEAR_DESC, RATING_DESC, UNWATCHED_FIRST
}

data class AppSettings(
    val jellyfinUrl: String = "",
    val jellyfinUserId: String = "",
    val jellyfinToken: String = "",
    val shimUrl: String = "",
    val downloadPath: String = "",
    val parentalPinHash: String = "",
    val isPinEnabled: Boolean = false,
    val episodeViewGrid: Boolean = true,
    val wifiOnly: Boolean = false,
    val showContinueWatching: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showMyList: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val introSkipEnabled: Boolean = true,
    val trickplayEnabled: Boolean = true,
    val downloadQueuePaused: Boolean = false,
    val maxConcurrentDownloads: Int = 1,
)
