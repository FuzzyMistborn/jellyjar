package com.fuzzymistborn.jellyjar.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─── Entities ─────────────────────────────────────────────────────────────────

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val jellyfinId: String,
    val title: String,
    val localPath: String,
    val status: String,             // matches DownloadStatus enum name
    val progress: Float,
    val sizeBytes: Long,
    val preset: String,
    val addedAt: Long,
    val shimJobId: String?,
    val thumbnailPath: String?,
    val overview: String?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val type: String,               // Movie / Episode
    val seriesName: String?,
    // Season/episode numbering, mirroring CachedItemEntity — needed to lay downloads out in a
    // Jellyfin-style folder tree (TV Shows/Series/Season NN/...) instead of one flat folder.
    val seasonName: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val mediaSourcePath: String? = null,
    val playbackPositionMs: Long = 0,
    // Order within the local download queue; only meaningful while status = QUEUED.
    val queuePosition: Long = 0,
    // Intro/credits skip segments (JSON array of SkipSegment) captured at queue time so the
    // skip button works during offline playback.
    val segmentsJson: String? = null,
    val played: Boolean = false,
    // The SAF document URI the file was written to, when the download folder is a content:// tree.
    // Deletion used to re-find the file by name under the tree, which silently no-ops if the file
    // was renamed or the folder re-picked — leaving an orphan the Storage screen can't see.
    val localUri: String? = null,
    // OutputTracks JSON from the finished Press job — which tracks are forced/default, since
    // the player can't read that from the MP4. Null for downloads made before this existed.
    val tracksJson: String? = null,
) {
    // thumbnailPath is a bare filesystem path (see DownloadRepository.saveThumbnailLocally); Coil
    // only resolves recognized URI schemes, so callers need the `file://` form to load it locally.
    val thumbnailUri: String? get() = thumbnailPath?.let { "file://$it" }
}

@Entity(tableName = "cached_items")
data class CachedItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val overview: String?,
    val year: Int?,
    val communityRating: Float?,
    val runtimeMinutes: Int?,
    val seriesName: String?,
    val seasonName: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val mediaSourcePath: String?,
    val cachedAt: Long,
    val played: Boolean = false,
)

// Stores playback position for any item, whether downloaded or stream-only.
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val jellyfinId: String,
    val positionMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val jellyfinId: String,
    val title: String,
    val type: String,
    val addedAt: Long = System.currentTimeMillis(),
)

// Playback state that couldn't reach Jellyfin when it happened (offline viewing, LAN server
// unreachable). One row per item — only the latest state matters — flushed by PlaybackSyncWorker
// once the server is reachable again. updatedAt is when the viewing actually happened, so the
// flush can tell whether the server has seen a *newer* play on another client since.
@Entity(tableName = "pending_playback_sync")
data class PendingPlaybackSync(
    @PrimaryKey val jellyfinId: String,
    val positionMs: Long,
    val played: Boolean,
    val updatedAt: Long,
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETE' ORDER BY addedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE jellyfinId = :id")
    suspend fun findById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE shimJobId = :jobId")
    suspend fun findByShimJobId(jobId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE jellyfinId = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE jellyfinId = :id")
    suspend fun updateProgress(id: String, progress: Float, status: String)

    @Query("UPDATE downloads SET playbackPositionMs = :positionMs WHERE jellyfinId = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long)

    @Query("UPDATE downloads SET thumbnailPath = :path WHERE jellyfinId = :id")
    suspend fun updateThumbnailPath(id: String, path: String)

    @Query("UPDATE downloads SET status = :status WHERE jellyfinId = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE downloads SET played = :played WHERE jellyfinId = :id")
    suspend fun updatePlayed(id: String, played: Boolean)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE status = 'COMPLETE'")
    suspend fun totalCompletedBytes(): Long

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY queuePosition ASC, addedAt ASC")
    suspend fun queuedInOrder(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('TRANSCODING', 'DOWNLOADING')")
    suspend fun countInFlight(): Int

    @Query("SELECT COALESCE(MAX(queuePosition), 0) FROM downloads WHERE status = 'QUEUED'")
    suspend fun maxQueuePosition(): Long

    @Query("SELECT COALESCE(MIN(queuePosition), 0) FROM downloads WHERE status = 'QUEUED'")
    suspend fun minQueuePosition(): Long

    @Query("UPDATE downloads SET queuePosition = :position WHERE jellyfinId = :id")
    suspend fun updateQueuePosition(id: String, position: Long)
}

@Dao
interface PlaybackPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackPositionEntity)

    @Query("SELECT positionMs FROM playback_positions WHERE jellyfinId = :id")
    suspend fun getPosition(id: String): Long?

    @Query("DELETE FROM playback_positions WHERE jellyfinId = :id")
    suspend fun delete(id: String)
}

@Dao
interface PendingPlaybackSyncDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPlaybackSync)

    @Query("DELETE FROM pending_playback_sync WHERE jellyfinId = :id")
    suspend fun delete(id: String)

    // Only removes the row if it hasn't been rewritten since it was read — a flush racing a new
    // offline stop for the same item mustn't delete the newer state it never synced.
    @Query("DELETE FROM pending_playback_sync WHERE jellyfinId = :id AND updatedAt = :updatedAt")
    suspend fun deleteIfUnchanged(id: String, updatedAt: Long)

    @Query("SELECT * FROM pending_playback_sync ORDER BY updatedAt ASC")
    suspend fun getAll(): List<PendingPlaybackSync>

    @Query("SELECT jellyfinId FROM pending_playback_sync")
    suspend fun pendingIds(): List<String>
}

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT jellyfinId FROM favorites")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE jellyfinId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) > 0 FROM favorites WHERE jellyfinId = :id")
    suspend fun isFavorite(id: String): Boolean
}

@Dao
interface CachedItemDao {

    @Query("SELECT * FROM cached_items ORDER BY name ASC")
    fun observeAll(): Flow<List<CachedItemEntity>>

    @Query("SELECT * FROM cached_items WHERE id = :id")
    suspend fun findById(id: String): CachedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedItemEntity>)

    @Query("DELETE FROM cached_items WHERE cachedAt < :before")
    suspend fun evictOlderThan(before: Long)

    @Query("DELETE FROM cached_items")
    suspend fun clearAll()

    @Query("SELECT * FROM cached_items WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByNameAndType(name: String, type: String): CachedItemEntity?

    @Query("""
        SELECT ci.* FROM cached_items ci
        WHERE ci.seriesName = :seriesName AND ci.type = 'Season'
          AND ci.indexNumber IS NOT NULL
          AND EXISTS (
            SELECT 1 FROM cached_items ep
            WHERE ep.seriesName = :seriesName
              AND ep.type = 'Episode'
              AND ep.parentIndexNumber = ci.indexNumber
              AND EXISTS (SELECT 1 FROM downloads d WHERE d.jellyfinId = ep.id AND d.status = 'COMPLETE')
          )
        ORDER BY ci.indexNumber ASC
    """)
    suspend fun findSeasonsBySeriesName(seriesName: String): List<CachedItemEntity>

    // Season numbers derived purely from downloaded Episode rows, not from any cached Season-type
    // row. Episode.seriesName is proven reliable elsewhere in this app (e.g. the "$seriesName ·
    // S01E01 · $name" display string), whereas a Season row's own seriesName column only exists if
    // the season list was fetched online and depends on Jellyfin actually populating that field on
    // Season API objects — unverified and a single point of failure findSeasonsBySeriesName has,
    // which is why it silently returned zero rows for a real downloaded episode.
    @Query("""
        SELECT DISTINCT ep.parentIndexNumber FROM cached_items ep
        WHERE ep.seriesName = :seriesName AND ep.type = 'Episode' AND ep.parentIndexNumber IS NOT NULL
          AND EXISTS (SELECT 1 FROM downloads d WHERE d.jellyfinId = ep.id AND d.status = 'COMPLETE')
        ORDER BY ep.parentIndexNumber ASC
    """)
    suspend fun findDownloadedSeasonNumbers(seriesName: String): List<Int>

    @Query("""
        SELECT ci.* FROM cached_items ci
        WHERE ci.seriesName = :seriesName AND ci.type = 'Episode' AND ci.parentIndexNumber = :seasonNumber
          AND EXISTS (SELECT 1 FROM downloads d WHERE d.jellyfinId = ci.id AND d.status = 'COMPLETE')
        ORDER BY ci.indexNumber ASC
    """)
    suspend fun findEpisodesBySeriesAndSeason(seriesName: String, seasonNumber: Int): List<CachedItemEntity>
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        DownloadEntity::class, CachedItemEntity::class, PlaybackPositionEntity::class,
        FavoriteEntity::class, PendingPlaybackSync::class,
    ],
    version = 11,
    // Exported to app/schemas (see room.schemaLocation in build.gradle.kts) so a real migration
    // can be written and reviewed once the app is distributed — see the destructive-migration
    // note in AppModule.
    exportSchema = true,
)
abstract class JellyJarDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun cachedItemDao(): CachedItemDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun pendingPlaybackSyncDao(): PendingPlaybackSyncDao
}

// The first real migration: adding a table is purely additive, so there's no reason to take the
// destructive fallback (and wipe every download record) for it. The SQL must match what Room
// generates for PendingPlaybackSync exactly, or Room's post-migration validation throws — compare
// against app/schemas/.../10.json if the entity ever changes.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_playback_sync` (" +
                "`jellyfinId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`played` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`jellyfinId`))"
        )
    }
}

// Adds DownloadEntity.tracksJson. A nullable TEXT column with no default is exactly what Room
// generates for a `String? = null` property, so validation passes and existing rows get NULL.
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `tracksJson` TEXT")
    }
}
