package com.fuzzymistborn.jellyjar.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {

    private val internal = FolderCandidate("internal", available = true, freeBytes = 10_000)
    private val sd = FolderCandidate("sd", available = true, freeBytes = 50_000)

    @Test
    fun usesFirstFolderInPriorityOrder() {
        assertEquals("internal", chooseFolder(listOf(internal, sd), requiredBytes = 1_000))
    }

    @Test
    fun spillsToNextFolderWhenFirstIsTooSmall() {
        assertEquals("sd", chooseFolder(listOf(internal, sd), requiredBytes = 20_000))
    }

    @Test
    fun skipsUnavailableFolder() {
        val ejected = sd.copy(available = false)
        assertEquals("internal", chooseFolder(listOf(ejected, internal), requiredBytes = 1_000))
    }

    @Test
    fun nullWhenNothingFits() {
        assertNull(chooseFolder(listOf(internal, sd), requiredBytes = 100_000))
        assertNull(chooseFolder(listOf(internal.copy(available = false)), requiredBytes = 1))
        assertNull(chooseFolder(emptyList(), requiredBytes = 1))
    }

    @Test
    fun seriesAffinityBeatsPriorityWhenItHasRoom() {
        assertEquals("sd", chooseFolder(listOf(internal, sd), requiredBytes = 1_000, preferredUri = "sd"))
    }

    @Test
    fun seriesAffinityIgnoredWhenPreferredFolderIsFullOrGone() {
        assertEquals("internal", chooseFolder(listOf(internal, sd), requiredBytes = 5_000, preferredUri = "gone"))
        val fullSd = sd.copy(freeBytes = 10)
        assertEquals("internal", chooseFolder(listOf(internal, fullSd), requiredBytes = 1_000, preferredUri = "sd"))
    }

    @Test
    fun overrideIsHonouredOverPriorityAndAffinity() {
        assertEquals(
            "sd",
            chooseFolder(listOf(internal, sd), requiredBytes = 1_000, preferredUri = "internal", overrideUri = "sd"),
        )
    }

    @Test
    fun overrideThatCannotFitFailsInsteadOfFallingBack() {
        assertNull(chooseFolder(listOf(internal, sd), requiredBytes = 20_000, overrideUri = "internal"))
        assertNull(chooseFolder(listOf(internal, sd.copy(available = false)), requiredBytes = 1_000, overrideUri = "sd"))
    }

    @Test
    fun overrideForRemovedFolderIsIgnored() {
        assertEquals("internal", chooseFolder(listOf(internal, sd), requiredBytes = 1_000, overrideUri = "removed"))
    }

    @Test
    fun docIdWithinTreeMatchesOnlyDescendants() {
        assertTrue(docIdWithinTree("1234-ABCD:Movies", "1234-ABCD:Movies/Title/file.mp4"))
        assertTrue(docIdWithinTree("1234-ABCD:Movies", "1234-ABCD:Movies"))
        assertFalse(docIdWithinTree("1234-ABCD:Movies", "1234-ABCD:Movies2/file.mp4"))
        assertFalse(docIdWithinTree("1234-ABCD:Movies", "primary:Movies/file.mp4"))
    }

    @Test
    fun docIdWithinVolumeRootTree() {
        assertTrue(docIdWithinTree("1234-ABCD:", "1234-ABCD:Movies/file.mp4"))
        assertFalse(docIdWithinTree("1234-ABCD:", "5678-EF01:Movies/file.mp4"))
    }
}
