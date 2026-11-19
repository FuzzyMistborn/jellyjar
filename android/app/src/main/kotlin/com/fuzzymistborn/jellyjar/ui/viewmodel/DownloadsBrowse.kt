package com.fuzzymistborn.jellyjar.ui.viewmodel

import com.fuzzymistborn.jellyjar.data.local.DownloadEntity

enum class DownloadSort(val label: String) {
    RECENT("Recent"),
    TITLE("A–Z"),
    LARGEST("Largest"),
    OLDEST("Oldest"),
}

enum class DownloadFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    TV("TV"),
    UNWATCHED("Unwatched"),
    WATCHED("Watched"),
}

data class DownloadsBrowse(
    val sort: DownloadSort = DownloadSort.RECENT,
    val filter: DownloadFilter = DownloadFilter.ALL,
    // A download folder URI; null = every folder.
    val folder: String? = null,
    val query: String = "",
) {
    val isFiltering: Boolean get() = filter != DownloadFilter.ALL || folder != null || query.isNotBlank()
}

// One season's downloaded episodes within a series.
data class DownloadSeason(val number: Int?, val episodes: List<DownloadEntity>) {
    val totalBytes: Long get() = episodes.sumOf { it.sizeBytes }
    val watchedCount: Int get() = episodes.count { it.isWatched }
    val label: String get() = number?.let { if (it == 0) "Specials" else "Season $it" } ?: "Episodes"
}

// One top-level row of the "Available Offline" list: a movie (or an episode with no series name)
// on its own, or every downloaded episode of one series folded together.
sealed interface DownloadEntry {
    val key: String
    val title: String
    val totalBytes: Long
    val newestAddedAt: Long
    val oldestAddedAt: Long

    data class Single(val download: DownloadEntity) : DownloadEntry {
        override val key get() = download.jellyfinId
        override val title get() = download.title
        override val totalBytes get() = download.sizeBytes
        override val newestAddedAt get() = download.addedAt
        override val oldestAddedAt get() = download.addedAt
    }

    data class Series(val name: String, val seasons: List<DownloadSeason>) : DownloadEntry {
        val episodes get() = seasons.flatMap { it.episodes }
        // The series' Jellyfin id when any episode recorded it, for the series poster.
        val seriesId get() = episodes.firstNotNullOfOrNull { it.seriesId }
        override val key get() = "series:$name"
        override val title get() = name
        override val totalBytes get() = episodes.sumOf { it.sizeBytes }
        override val newestAddedAt get() = episodes.maxOf { it.addedAt }
        override val oldestAddedAt get() = episodes.minOf { it.addedAt }
        val watchedCount get() = episodes.count { it.isWatched }
    }
}

// Search, filter, group and sort the finished downloads. Filters apply to individual downloads
// before grouping, so a series shows only the episodes that match (and disappears if none do).
// Seasons and the episodes inside them always run in order whatever the sort is.
// [folderById] maps a download id to the download folder it lives in.
fun browseDownloads(
    downloads: List<DownloadEntity>,
    browse: DownloadsBrowse,
    folderById: Map<String, String>,
): List<DownloadEntry> {
    val query = browse.query.trim()
    val matching = downloads.filter { d ->
        val isEpisode = d.type == "Episode"
        val filterOk = when (browse.filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.MOVIES -> !isEpisode
            DownloadFilter.TV -> isEpisode
            DownloadFilter.UNWATCHED -> !d.isWatched
            DownloadFilter.WATCHED -> d.isWatched
        }
        filterOk &&
            (browse.folder == null || folderById[d.jellyfinId] == browse.folder) &&
            (query.isEmpty() ||
                d.title.contains(query, ignoreCase = true) ||
                d.seriesName?.contains(query, ignoreCase = true) == true)
    }

    val (episodes, others) = matching.partition { it.type == "Episode" && it.seriesName != null }
    val series = episodes.groupBy { it.seriesName!! }.map { (name, eps) ->
        DownloadEntry.Series(
            name,
            eps.groupBy { it.parentIndexNumber }
                .map { (num, inSeason) ->
                    DownloadSeason(
                        num,
                        inSeason.sortedWith(
                            compareBy<DownloadEntity>({ it.indexNumber ?: Int.MAX_VALUE }).thenBy { it.title.lowercase() },
                        ),
                    )
                }
                .sortedBy { it.number ?: Int.MAX_VALUE },
        )
    }
    val entries: List<DownloadEntry> = series + others.map { DownloadEntry.Single(it) }

    return when (browse.sort) {
        DownloadSort.RECENT -> entries.sortedByDescending { it.newestAddedAt }
        DownloadSort.OLDEST -> entries.sortedBy { it.oldestAddedAt }
        DownloadSort.LARGEST -> entries.sortedByDescending { it.totalBytes }
        DownloadSort.TITLE -> entries.sortedBy { it.title.lowercase().removePrefix("the ") }
    }
}

// In-progress and queued rows: episodes of one series fold into a single group once there are two
// or more of them, so a queued season is one row instead of twenty. Groups keep the order of
// their first member, which is the order the queue itself runs in.
sealed interface QueueGroup {
    val key: String

    data class Single(val download: DownloadEntity) : QueueGroup {
        override val key get() = download.jellyfinId
    }

    data class Series(val name: String, val items: List<DownloadEntity>) : QueueGroup {
        override val key get() = "series:$name"
    }
}

fun groupQueue(items: List<DownloadEntity>): List<QueueGroup> {
    val bySeries = items.filter { it.type == "Episode" && it.seriesName != null }.groupBy { it.seriesName!! }
    val seen = mutableSetOf<String>()
    return buildList {
        for (d in items) {
            val series = d.seriesName?.takeIf { d.type == "Episode" && bySeries[it]!!.size > 1 }
            if (series == null) add(QueueGroup.Single(d))
            else if (seen.add(series)) add(QueueGroup.Series(series, bySeries.getValue(series)))
        }
    }
}
