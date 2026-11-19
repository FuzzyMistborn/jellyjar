package com.fuzzymistborn.jellyjar.ui.viewmodel

enum class ReadyFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    TV("TV"),
    NOT_DOWNLOADED("Not downloaded"),
}

data class ReadyBrowse(
    val sort: DownloadSort = DownloadSort.RECENT,
    val filter: ReadyFilter = ReadyFilter.ALL,
    val query: String = "",
) {
    val isFiltering: Boolean get() = filter != ReadyFilter.ALL || query.isNotBlank()
}

// One season's worth of finished files for a series.
data class ReadySeason(val number: Int?, val episodes: List<ReadyOnPressRow>) {
    val totalBytes: Long get() = episodes.sumOf { it.entry.size_bytes }
    val label: String get() = number?.let { if (it == 0) "Specials" else "Season $it" } ?: "Episodes"
}

// One top-level row of the Ready on Press list: a movie (or an episode with no series name) on its
// own, or every ready episode of one series, split by season.
sealed interface ReadyGroup {
    val key: String
    val title: String
    val totalBytes: Long
    val newestUpdate: String
    val oldestUpdate: String

    data class Single(val row: ReadyOnPressRow) : ReadyGroup {
        override val key get() = row.entry.job_id
        override val title get() = row.item.name
        override val totalBytes get() = row.entry.size_bytes
        override val newestUpdate get() = row.entry.updated_at
        override val oldestUpdate get() = row.entry.updated_at
    }

    data class Series(val name: String, val seasons: List<ReadySeason>) : ReadyGroup {
        val episodes get() = seasons.flatMap { it.episodes }
        override val key get() = "series:$name"
        override val title get() = name
        override val totalBytes get() = seasons.sumOf { it.totalBytes }
        override val newestUpdate get() = episodes.maxOf { it.entry.updated_at }
        override val oldestUpdate get() = episodes.minOf { it.entry.updated_at }
    }
}

// Search, filter, group and sort what's ready on Press, like browseDownloads() does for the device.
// Filters apply to single files before grouping, so a series shows only the episodes that match.
// [downloaded] is the set of Jellyfin ids already on the device or on their way (not failed ones).
// updated_at is an ISO-8601 UTC timestamp, so plain string comparison orders it.
fun browseReady(
    rows: List<ReadyOnPressRow>,
    browse: ReadyBrowse,
    downloaded: Set<String>,
): List<ReadyGroup> {
    val query = browse.query.trim()
    val matching = rows.filter { r ->
        val isEpisode = r.item.type == "Episode"
        val filterOk = when (browse.filter) {
            ReadyFilter.ALL -> true
            ReadyFilter.MOVIES -> !isEpisode
            ReadyFilter.TV -> isEpisode
            ReadyFilter.NOT_DOWNLOADED -> r.item.id !in downloaded
        }
        filterOk && (query.isEmpty() ||
            r.item.name.contains(query, ignoreCase = true) ||
            r.item.seriesName?.contains(query, ignoreCase = true) == true)
    }

    val (episodes, others) = matching.partition { it.item.type == "Episode" && it.item.seriesName != null }
    val series = episodes.groupBy { it.item.seriesName!! }.map { (name, eps) ->
        ReadyGroup.Series(
            name,
            eps.groupBy { it.item.parentIndexNumber }
                .map { (num, inSeason) ->
                    ReadySeason(
                        num,
                        inSeason.sortedWith(
                            compareBy<ReadyOnPressRow>({ it.item.indexNumber ?: Int.MAX_VALUE }, { it.item.name.lowercase() }),
                        ),
                    )
                }
                .sortedBy { it.number ?: Int.MAX_VALUE },
        )
    }
    val groups: List<ReadyGroup> = series + others.map { ReadyGroup.Single(it) }

    return when (browse.sort) {
        DownloadSort.RECENT -> groups.sortedByDescending { it.newestUpdate }
        DownloadSort.OLDEST -> groups.sortedBy { it.oldestUpdate }
        DownloadSort.LARGEST -> groups.sortedByDescending { it.totalBytes }
        DownloadSort.TITLE -> groups.sortedBy { it.title.lowercase().removePrefix("the ") }
    }
}
