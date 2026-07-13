package com.fuzzymistborn.jellyjar.data.local

import androidx.room.*
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
    val mediaSourcePath: String? = null,
    val playbackPositionMs: Long = 0,
    // Order within the local download queue; only meaningful while status = QUEUED.
    val queuePosition: Long = 0,
)

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

    @Query("SELECT * FROM cached_items WHERE seriesName = :seriesName AND type = 'Episode' AND parentIndexNumber = :seasonNumber ORDER BY indexNumber ASC")
    suspend fun findEpisodesBySeriesAndSeason(seriesName: String, seasonNumber: Int): List<CachedItemEntity>
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [DownloadEntity::class, CachedItemEntity::class, PlaybackPositionEntity::class, FavoriteEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class JellyJarDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun cachedItemDao(): CachedItemDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun favoriteDao(): FavoriteDao
}
