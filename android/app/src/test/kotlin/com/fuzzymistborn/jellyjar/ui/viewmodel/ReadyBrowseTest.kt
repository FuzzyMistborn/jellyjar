package com.fuzzymistborn.jellyjar.ui.viewmodel

import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.model.ReadyEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadyBrowseTest {

    private fun row(
        id: String,
        name: String,
        type: String = "Movie",
        series: String? = null,
        season: Int? = null,
        episode: Int? = null,
        size: Long = 100,
        updated: String = "2026-01-01T00:00:00",
    ) = ReadyOnPressRow(
        ReadyEntry(job_id = "job-$id", item_id = id, display_name = name, preset = "720p", size_bytes = size, duration_seconds = null, updated_at = updated),
        JellyfinItem(
            id = id, name = name, type = type, overview = null, year = null, communityRating = null,
            runTimeTicks = null, seriesName = series, seasonName = null, indexNumber = episode,
            parentIndexNumber = season, mediaSources = null, imageTags = null, backdropImageTags = null,
            userData = null,
        ),
    )

    private val all = listOf(
        row("m1", "The Zebra Movie", size = 900, updated = "2026-01-10"),
        row("e12", "Twelve", "Episode", "Bluey", 1, 12, size = 50, updated = "2026-01-20"),
        row("e21", "Other season", "Episode", "Bluey", 2, 1, size = 50, updated = "2026-01-30"),
        row("e11", "Eleven", "Episode", "Bluey", 1, 11, size = 50, updated = "2026-01-05"),
        row("a1", "Pilot", "Episode", "Ants", 1, 1, size = 700, updated = "2026-01-02"),
    )

    private fun keys(browse: ReadyBrowse, downloaded: Set<String> = emptySet()) =
        browseReady(all, browse, downloaded).map { it.key }

    @Test
    fun splitsASeriesIntoOrderedSeasons() {
        val bluey = browseReady(all, ReadyBrowse(), emptySet())
            .filterIsInstance<ReadyGroup.Series>().first { it.name == "Bluey" }
        assertEquals(listOf(1, 2), bluey.seasons.map { it.number })
        assertEquals(listOf("e11", "e12"), bluey.seasons[0].episodes.map { it.item.id })
        assertEquals("Season 1", bluey.seasons[0].label)
    }

    @Test
    fun sortsGroupsByTheirNewestOrOldestFile() {
        assertEquals(listOf("series:Bluey", "job-m1", "series:Ants"), keys(ReadyBrowse()))
        assertEquals(listOf("series:Ants", "series:Bluey", "job-m1"), keys(ReadyBrowse(sort = DownloadSort.OLDEST)))
    }

    @Test
    fun largestSumsAGroup() {
        assertEquals(listOf("job-m1", "series:Ants", "series:Bluey"), keys(ReadyBrowse(sort = DownloadSort.LARGEST)))
    }

    @Test
    fun titleSortIgnoresLeadingThe() {
        assertEquals(listOf("series:Ants", "series:Bluey", "job-m1"), keys(ReadyBrowse(sort = DownloadSort.TITLE)))
    }

    @Test
    fun notDownloadedHidesFilesAlreadyOnTheDevice() {
        val result = browseReady(all, ReadyBrowse(filter = ReadyFilter.NOT_DOWNLOADED), setOf("e11", "a1"))
        assertEquals(listOf("e12", "e21"), result.filterIsInstance<ReadyGroup.Series>().single().episodes.map { it.item.id })
        assertEquals(2, result.size)
    }

    @Test
    fun typeFilters() {
        assertEquals(listOf("job-m1"), keys(ReadyBrowse(filter = ReadyFilter.MOVIES)))
        assertEquals(setOf("series:Bluey", "series:Ants"), keys(ReadyBrowse(filter = ReadyFilter.TV)).toSet())
    }

    @Test
    fun searchMatchesSeriesNameOrTitle() {
        assertEquals(listOf("series:Bluey"), keys(ReadyBrowse(query = "bluey")))
        val one = browseReady(all, ReadyBrowse(query = "eleven"), emptySet()).filterIsInstance<ReadyGroup.Series>().single()
        assertEquals(listOf("e11"), one.episodes.map { it.item.id })
    }
}
