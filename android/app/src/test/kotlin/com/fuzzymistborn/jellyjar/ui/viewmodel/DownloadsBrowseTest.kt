package com.fuzzymistborn.jellyjar.ui.viewmodel

import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsBrowseTest {

    private fun dl(
        id: String,
        title: String,
        type: String = "Movie",
        series: String? = null,
        season: Int? = null,
        episode: Int? = null,
        size: Long = 100,
        added: Long = 0,
        played: Boolean = false,
    ) = DownloadEntity(
        jellyfinId = id, title = title, localPath = "", status = "COMPLETE", progress = 1f,
        sizeBytes = size, preset = "720p", addedAt = added, shimJobId = null, thumbnailPath = null,
        overview = null, year = null, runtimeMinutes = null, type = type, seriesName = series,
        parentIndexNumber = season, indexNumber = episode, played = played,
    )

    private val all = listOf(
        dl("m1", "The Zebra Movie", size = 900, added = 10),
        dl("m2", "Apple Movie", size = 150, added = 30, played = true),
        dl("e2", "Second", "Episode", "Bluey", 1, 2, size = 50, added = 20),
        dl("e1", "First", "Episode", "Bluey", 1, 1, size = 50, added = 40),
        dl("e3", "Pilot", "Episode", "Ants", 1, 1, size = 700, added = 5),
    )

    private fun keys(browse: DownloadsBrowse, folders: Map<String, String> = emptyMap()) =
        browseDownloads(all, browse, folders).map { it.key }

    @Test
    fun groupsEpisodesBySeriesInEpisodeOrder() {
        val bluey = browseDownloads(all, DownloadsBrowse(), emptyMap())
            .filterIsInstance<DownloadEntry.Series>().first { it.name == "Bluey" }
        assertEquals(listOf("e1", "e2"), bluey.episodes.map { it.jellyfinId })
    }

    @Test
    fun splitsASeriesIntoSeasonsInOrder() {
        val items = listOf(
            dl("b3", "Third", "Episode", "Show", 2, 1),
            dl("b1", "First", "Episode", "Show", 1, 2),
            dl("b2", "Zero", "Episode", "Show", 1, 1),
        )
        val show = browseDownloads(items, DownloadsBrowse(), emptyMap()).filterIsInstance<DownloadEntry.Series>().single()
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf("b2", "b1"), show.seasons[0].episodes.map { it.jellyfinId })
        assertEquals("Season 2", show.seasons[1].label)
    }

    @Test
    fun recentUsesNewestEpisodeOfAGroup() {
        assertEquals(listOf("series:Bluey", "m2", "m1", "series:Ants"), keys(DownloadsBrowse()))
    }

    @Test
    fun oldestUsesOldestEpisodeOfAGroup() {
        assertEquals(listOf("series:Ants", "m1", "series:Bluey", "m2"), keys(DownloadsBrowse(sort = DownloadSort.OLDEST)))
    }

    @Test
    fun largestSumsAGroup() {
        assertEquals(listOf("m1", "series:Ants", "m2", "series:Bluey"), keys(DownloadsBrowse(sort = DownloadSort.LARGEST)))
    }

    @Test
    fun titleSortIgnoresLeadingThe() {
        assertEquals(
            listOf("series:Ants", "m2", "series:Bluey", "m1"),
            keys(DownloadsBrowse(sort = DownloadSort.TITLE)),
        )
    }

    @Test
    fun typeFilters() {
        assertEquals(setOf("m1", "m2"), keys(DownloadsBrowse(filter = DownloadFilter.MOVIES)).toSet())
        assertEquals(setOf("series:Bluey", "series:Ants"), keys(DownloadsBrowse(filter = DownloadFilter.TV)).toSet())
    }

    @Test
    fun watchedFilterDropsWholeSeriesWhenNothingMatches() {
        assertEquals(listOf("m2"), keys(DownloadsBrowse(filter = DownloadFilter.WATCHED)))
    }

    @Test
    fun searchMatchesSeriesNameAndKeepsOnlyMatchingEpisodes() {
        val result = browseDownloads(all, DownloadsBrowse(query = "bluey"), emptyMap())
        assertEquals(listOf("series:Bluey"), result.map { it.key })
        val byTitle = browseDownloads(all, DownloadsBrowse(query = "second"), emptyMap())
            .filterIsInstance<DownloadEntry.Series>().single()
        assertEquals(listOf("e2"), byTitle.episodes.map { it.jellyfinId })
    }

    @Test
    fun folderFilter() {
        val folders = mapOf("m1" to "sd", "m2" to "internal", "e1" to "sd", "e2" to "sd", "e3" to "internal")
        assertEquals(setOf("m1", "series:Bluey"), keys(DownloadsBrowse(folder = "sd"), folders).toSet())
    }

    @Test
    fun queueFoldsASeriesAtItsFirstPosition() {
        val queue = listOf(
            dl("a1", "A1", "Episode", "Alpha", 1, 1),
            dl("m", "Movie"),
            dl("a2", "A2", "Episode", "Alpha", 1, 2),
            dl("b1", "B1", "Episode", "Beta", 1, 1),
        )
        val groups = groupQueue(queue)
        assertEquals(listOf("series:Alpha", "m", "b1"), groups.map { it.key })
        assertEquals(listOf("a1", "a2"), (groups[0] as QueueGroup.Series).items.map { it.jellyfinId })
    }
}
