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

@Entity(tableName = "snapshots", indices = [Index("key")])
data class SnapshotEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val key: String, val name: String, val json: String, val createdAt: Long)

@Entity(tableName = "presets")
data class PresetEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val json: String, val createdAt: Long)

data class KnownRow(val id: Long, val uri: String, val modified: Long, val size: Long)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE folderUri = :folder ORDER BY modified DESC, id DESC")
    fun observe(folder: String): Flow<List<PhotoEntity>>

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

@Database(entities = [PhotoEntity::class, EditEntity::class, SnapshotEntity::class, PresetEntity::class, MetaEntity::class], version = 2, exportSchema = false)
abstract class RawlineDb : RoomDatabase() {
    abstract fun photos(): PhotoDao
    abstract fun edits(): EditDao

    companion object {
        fun create(context: Context): RawlineDb =
            Room.databaseBuilder(context, RawlineDb::class.java, "rawline.db").fallbackToDestructiveMigrationFrom(true, 1).build()
    }
}
