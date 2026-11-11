package com.fuzzymistborn.jellyjar.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─── Folder choice (pure, so it can be unit-tested) ──────────────────────────

data class FolderCandidate(val uri: String, val available: Boolean, val freeBytes: Long)

// Picks the folder a download is written to. Folders are in the user's priority order.
//  - overrideUri ("Save to…") is honoured strictly: if that folder can't take the file the result
//    is null rather than quietly landing somewhere the user didn't ask for. An override that is no
//    longer a configured folder is ignored.
//  - otherwise preferredUri (where the same series already lives) wins if it has room, then the
//    first folder in priority order that is mounted and has room.
fun chooseFolder(
    folders: List<FolderCandidate>,
    requiredBytes: Long,
    preferredUri: String? = null,
    overrideUri: String? = null,
): String? {
    fun fits(c: FolderCandidate) = c.available && c.freeBytes >= requiredBytes
    if (overrideUri != null) {
        folders.firstOrNull { it.uri == overrideUri }?.let { return it.uri.takeIf { _ -> fits(it) } }
    }
    val usable = folders.filter(::fits)
    return usable.firstOrNull { it.uri == preferredUri }?.uri ?: usable.firstOrNull()?.uri
}

// Whether a SAF document id ("XXXX-XXXX:Movies/Title/file.mp4") sits inside a tree's document id
// ("XXXX-XXXX:Movies"). A tree at a volume root has an id ending in ':' with no separator to add.
internal fun docIdWithinTree(treeDocId: String, docId: String): Boolean = when {
    docId == treeDocId -> true
    treeDocId.endsWith(":") -> docId.startsWith(treeDocId)
    else -> docId.startsWith(treeDocId.trimEnd('/') + "/")
}

// ─── Storage volumes ──────────────────────────────────────────────────────────

data class FolderInfo(
    val uri: String,
    val label: String,
    // Mounted, readable, and the app still holds its write grant.
    val available: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    // Folders on the same volume share free space, so totals must not be summed per folder.
    val volumeKey: String,
)

@Singleton
class DownloadStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val FREE_SPACE_REFRESH_MS = 15_000L
    }

    // Maps a tree URI to the volume it lives on. SAF tree ids start with the volume id: "primary"
    // for internal shared storage, the volume UUID for an SD card or USB drive. Null when the
    // volume isn't mounted — which is exactly how an ejected card shows up.
    private fun volumeOf(uri: Uri): StorageVolume? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val volumeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.substringBefore(':') ?: return null
        return if (volumeId == "primary") {
            storageManager.primaryStorageVolume
        } else {
            storageManager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
        }
    }

    private fun hasWriteGrant(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isWritePermission && it.uri == uri }

    // Blocking (StatFs) — call off the main thread.
    fun info(folder: String): FolderInfo {
        if (!folder.startsWith("content://")) {
            // A plain filesystem path from before the folder picker was the only way in.
            val stat = runCatching { StatFs(folder) }.getOrNull()
            return FolderInfo(
                uri = folder,
                label = folder,
                available = File(folder).isDirectory && stat != null,
                freeBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L,
                volumeKey = "path:$folder",
            )
        }
        val uri = Uri.parse(folder)
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        val subPath = treeId.substringAfter(':', "").trim('/')
        val volume = volumeOf(uri)
        val volumeName = volume?.getDescription(context)
            ?: treeId.substringBefore(':').ifBlank { "Unknown storage" }
        val label = if (subPath.isEmpty()) volumeName else "$volumeName / $subPath"

        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            // Some other document provider (a folder picked before volumes were tracked, or a
            // third-party provider): there is no volume to measure or eject, so it is usable
            // while the grant holds and the folder still resolves. Free space is reported for
            // internal storage, which is what such a folder was measured against before.
            val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
            val resolves = runCatching { DocumentFile.fromTreeUri(context, uri)?.exists() == true }.getOrDefault(false)
            return FolderInfo(
                uri = folder,
                label = label,
                available = resolves && hasWriteGrant(uri),
                freeBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L,
                volumeKey = "provider:${uri.authority}",
            )
        }

        val mounted = volume != null && volume.state == Environment.MEDIA_MOUNTED
        val stat = if (mounted) {
            runCatching { StatFs(volume!!.directory!!.path) }.getOrNull()
        } else null
        return FolderInfo(
            uri = folder,
            label = label,
            available = stat != null && hasWriteGrant(uri),
            freeBytes = stat?.availableBytes ?: 0L,
            totalBytes = stat?.totalBytes ?: 0L,
            volumeKey = when {
                volume == null -> "missing:${treeId.substringBefore(':')}"
                volume.isPrimary -> "primary"
                else -> volume.uuid ?: volume.directory?.path ?: "volume:${treeId.substringBefore(':')}"
            },
        )
    }

    fun candidates(folders: List<String>): List<FolderCandidate> =
        folders.map { info(it) }.map { FolderCandidate(it.uri, it.available, it.freeBytes) }

    // Emits whenever a volume is mounted, unmounted or ejected.
    private fun volumeChanges(): Flow<Unit> = callbackFlow {
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                trySend(Unit)
            }
        }
        storageManager.registerStorageVolumeCallback(context.mainExecutor, callback)
        trySend(Unit)
        awaitClose { storageManager.unregisterStorageVolumeCallback(callback) }
    }

    // Free space changes as downloads land, with no volume event to say so, so it is re-read on a
    // timer while anything is collecting. Unchanged results don't propagate (FolderInfo is a data
    // class and the collectors are StateFlows).
    private fun freeSpaceTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(FREE_SPACE_REFRESH_MS)
        }
    }

    // Live status of every configured folder, in priority order. Re-evaluated on volume changes,
    // whenever the folder list changes, and every FREE_SPACE_REFRESH_MS for free space.
    // Shared: every screen collects this, and each cold collection would register its own volume
    // callback and re-read every folder on each event.
    val folderInfos: Flow<List<FolderInfo>> =
        combine(
            settings.settings.map { it.downloadFolders }.distinctUntilChanged(),
            volumeChanges(),
            freeSpaceTicks(),
        ) { folders, _, _ -> folders.map { info(it) } }
            .flowOn(Dispatchers.IO)
            .shareIn(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                SharingStarted.WhileSubscribed(5_000),
                replay = 1,
            )

    // Folders whose storage can't be reached right now (card ejected, grant lost).
    val unavailableFolders: Flow<Set<String>> =
        folderInfos.map { infos -> infos.filterNot { it.available }.map { it.uri }.toSet() }
            .distinctUntilChanged()

    fun treeContains(folder: String, documentUri: String): Boolean = runCatching {
        val tree = Uri.parse(folder)
        val doc = Uri.parse(documentUri)
        tree.authority == doc.authority &&
            docIdWithinTree(DocumentsContract.getTreeDocumentId(tree), DocumentsContract.getDocumentId(doc))
    }.getOrDefault(false)

    // Whether two folder trees are the same or one is inside the other.
    fun treesOverlap(a: String, b: String): Boolean = runCatching {
        val ua = Uri.parse(a)
        val ub = Uri.parse(b)
        ua.authority == ub.authority && DocumentsContract.getTreeDocumentId(ua).let { ia ->
            DocumentsContract.getTreeDocumentId(ub).let { ib -> docIdWithinTree(ia, ib) || docIdWithinTree(ib, ia) }
        }
    }.getOrDefault(false)

    // The configured folder a download lives in. Rows from before multiple folders have no
    // storageRoot, so fall back to matching the file's URI against the folders.
    fun rootOf(entity: DownloadEntity, folders: List<String>): String? =
        entity.storageRoot ?: entity.localUri?.let { doc -> folders.firstOrNull { treeContains(it, doc) } }

    fun isUnavailable(entity: DownloadEntity, folders: List<String>, unavailable: Set<String>): Boolean {
        if (unavailable.isEmpty()) return false
        val root = rootOf(entity, folders) ?: return false
        return root in unavailable
    }

    fun takeGrant(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun releaseGrant(folder: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
