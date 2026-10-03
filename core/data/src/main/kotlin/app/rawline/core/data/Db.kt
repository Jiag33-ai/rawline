package app.rawline.core.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photos", indices = [Index("folderUri"), Index(value = ["uri"], unique = true)])
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderUri: String,
    val uri: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val isRaw: Boolean,
    val indexed: Boolean = false,
    val takenAt: Long = 0,
    val camera: String? = null,
    val lens: String? = null,
    val iso: Int = 0,
    val shutter: Double = 0.0,
    val aperture: Double = 0.0,
    val focal: Double = 0.0,
    val orientation: Int = 1,
    val previewOffset: Long = 0,
    val previewLength: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rating: Int = 0,
    val flag: Int = 0,          // 0 none, 1 pick, -1 reject
    val label: Int = 0,         // colour label index, 0 none
    val edited: Boolean = false,
) {
    fun toModel() = Photo(
        id, folderUri, uri, name, size, modified, if (isRaw) Kind.RAW else Kind.IMAGE, indexed,
        takenAt, camera, lens, iso, shutter, aperture, focal, orientation, previewOffset, previewLength, width, height,
        rating, flag, label, edited,
    )
}

/** Latest edit of a photo. Keyed by a stable content key so edits survive re-indexing and reinstalls from backup. */
@Entity(tableName = "edits")
data class EditEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

/** Rating, flag and colour label, kept by content key so they survive re-indexing. */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val rating: Int, val flag: Int, val label: Int)

/** One entry of the export queue. status: 0 waiting, 1 running, 2 done, 3 failed, 4 cancelled. */
@Entity(tableName = "export_jobs")
data class ExportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoKey: String, val photoUri: String, val photoName: String, val settingsJson: String,
    val status: Int = 0, val progress: Float = 0f, val message: String? = null, val outputUri: String? = null, val createdAt: Long = 0,
)

@Dao
interface ExportDao {
    @Query("SELECT * FROM export_jobs ORDER BY id DESC") fun observe(): Flow<List<ExportJobEntity>>
    @Insert suspend fun add(jobs: List<ExportJobEntity>)
    @Query("SELECT * FROM export_jobs WHERE status = 0 ORDER BY id ASC LIMIT 1") suspend fun nextWaiting(): ExportJobEntity?
    @Query("SELECT COUNT(*) FROM export_jobs WHERE status IN (0, 1)") suspend fun activeCount(): Int
    @Query("UPDATE export_jobs SET status = :status, message = :message, outputUri = :out, progress = :progress WHERE id = :id")
    suspend fun finish(id: Long, status: Int, message: String?, out: String?, progress: Float)
    @Query("UPDATE export_jobs SET status = 1, progress = 0 WHERE id = :id") suspend fun start(id: Long)
    @Query("UPDATE export_jobs SET progress = :p WHERE id = :id") suspend fun progress(id: Long, p: Float)
    @Query("UPDATE export_jobs SET status = 0, progress = 0, message = NULL WHERE status IN (1, 3, 4) AND id = :id") suspend fun retry(id: Long)
    @Query("UPDATE export_jobs SET status = 0 WHERE status = 1") suspend fun resetRunning()
    @Query("UPDATE export_jobs SET status = 4, message = 'Cancelled' WHERE status = 0") suspend fun cancelWaiting()
    @Query("UPDATE export_jobs SET status = 4, message = 'Cancelled' WHERE id = :id AND status = 0") suspend fun cancel(id: Long)
    @Query("DELETE FROM export_jobs WHERE status IN (2, 3, 4)") suspend fun clearFinished()
    @Query("DELETE FROM export_jobs WHERE id = :id") suspend fun delete(id: Long)
}

@Entity(tableName = "snapshots", indices = [Index("key")])
data class SnapshotEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val key: String, val name: String, val json: String, val createdAt: Long)

@Entity(tableName = "presets")
data class PresetEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val json: String, val createdAt: Long)

data class KnownRow(val id: Long, val uri: String, val modified: Long, val size: Long)
data class SourceCount(val source: String, val n: Int)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE folderUri = :folder ORDER BY modified DESC, id DESC")
    fun observe(folder: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE folderUri LIKE :prefix ORDER BY modified DESC, id DESC")
    fun observeLike(prefix: String): Flow<List<PhotoEntity>>

    @Query("SELECT folderUri AS source, COUNT(*) AS n FROM photos GROUP BY folderUri")
    fun sources(): Flow<List<SourceCount>>

    @Query("SELECT * FROM photos WHERE folderUri LIKE :prefix AND indexed = 0 ORDER BY modified DESC")
    suspend fun pendingLike(prefix: String): List<PhotoEntity>

    @Query("SELECT id, uri, modified, size FROM photos WHERE folderUri LIKE :prefix")
    suspend fun knownLike(prefix: String): List<KnownRow>

    @Query("SELECT id, uri, modified, size FROM photos WHERE folderUri = :folder")
    suspend fun known(folder: String): List<KnownRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<PhotoEntity>)

    @Query("SELECT * FROM photos WHERE folderUri = :folder AND indexed = 0 ORDER BY modified DESC")
    suspend fun pending(folder: String): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("""UPDATE photos SET indexed = 1, takenAt = :takenAt, camera = :camera, lens = :lens, iso = :iso,
        shutter = :shutter, aperture = :aperture, focal = :focal, orientation = :orientation,
        previewOffset = :previewOffset, previewLength = :previewLength, width = :width, height = :height WHERE id = :id""")
    suspend fun markIndexed(
        id: Long, takenAt: Long, camera: String?, lens: String?, iso: Int, shutter: Double, aperture: Double,
        focal: Double, orientation: Int, previewOffset: Long, previewLength: Int, width: Int, height: Int,
    )

    @Query("UPDATE photos SET indexed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)

    @Query("UPDATE photos SET rating = :rating WHERE id IN (:ids)")
    suspend fun setRating(ids: List<Long>, rating: Int)

    @Query("UPDATE photos SET flag = :flag WHERE id IN (:ids)")
    suspend fun setFlag(ids: List<Long>, flag: Int)

    @Query("UPDATE photos SET label = :label WHERE id IN (:ids)")
    suspend fun setLabel(ids: List<Long>, label: Int)

    @Query("UPDATE photos SET edited = :edited WHERE id = :id")
    suspend fun setEdited(id: Long, edited: Boolean)

    @Query("UPDATE photos SET rating = :rating, label = :label WHERE uri = :uri AND rating = 0 AND label = 0")
    suspend fun setRatingLabelIfUnset(uri: String, rating: Int, label: Int)

    @Query("SELECT * FROM photos WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): PhotoEntity?

    @Query("SELECT * FROM photos")
    suspend fun all(): List<PhotoEntity>

    @Query("UPDATE photos SET rating = :rating, flag = :flag, label = :label, edited = :edited WHERE uri = :uri")
    suspend fun restoreMeta(uri: String, rating: Int, flag: Int, label: Int, edited: Boolean)
}

@Dao
interface EditDao {
    @Query("SELECT * FROM edits WHERE `key` = :key") suspend fun get(key: String): EditEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: EditEntity)
    @Query("DELETE FROM edits WHERE `key` = :key") suspend fun delete(key: String)
    @Query("SELECT * FROM edits") suspend fun all(): List<EditEntity>

    @Query("SELECT * FROM snapshots WHERE `key` = :key ORDER BY createdAt DESC") suspend fun snapshots(key: String): List<SnapshotEntity>
    @Insert suspend fun addSnapshot(s: SnapshotEntity)
    @Query("DELETE FROM snapshots WHERE id = :id") suspend fun deleteSnapshot(id: Long)
    @Query("SELECT * FROM snapshots") suspend fun allSnapshots(): List<SnapshotEntity>

    @Query("SELECT * FROM meta WHERE `key` IN (:keys)") suspend fun metaFor(keys: List<String>): List<MetaEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(m: List<MetaEntity>)
    @Query("SELECT * FROM meta") suspend fun allMeta(): List<MetaEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEdits(e: List<EditEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSnapshots(e: List<SnapshotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPresets(e: List<PresetEntity>)

    @Query("SELECT * FROM presets ORDER BY createdAt DESC") suspend fun presets(): List<PresetEntity>
    @Insert suspend fun addPreset(p: PresetEntity)
    @Query("DELETE FROM presets WHERE id = :id") suspend fun deletePreset(id: Long)
}

@Database(entities = [PhotoEntity::class, EditEntity::class, SnapshotEntity::class, PresetEntity::class, MetaEntity::class, ExportJobEntity::class], version = 3, exportSchema = false)
abstract class RawlineDb : RoomDatabase() {
    abstract fun photos(): PhotoDao
    abstract fun edits(): EditDao
    abstract fun exports(): ExportDao

    companion object {
        fun create(context: Context): RawlineDb =
            Room.databaseBuilder(context, RawlineDb::class.java, "rawline.db").addMigrations(object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `export_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoKey` TEXT NOT NULL, `photoUri` TEXT NOT NULL, `photoName` TEXT NOT NULL, `settingsJson` TEXT NOT NULL, `status` INTEGER NOT NULL, `progress` REAL NOT NULL, `message` TEXT, `outputUri` TEXT, `createdAt` INTEGER NOT NULL)")
                }
            }).fallbackToDestructiveMigrationFrom(true, 1).build()
    }
}
