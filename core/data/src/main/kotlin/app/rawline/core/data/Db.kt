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
) {
    fun toModel() = Photo(
        id, folderUri, uri, name, size, modified, if (isRaw) Kind.RAW else Kind.IMAGE, indexed,
        takenAt, camera, lens, iso, shutter, aperture, focal, orientation, previewOffset, previewLength, width, height,
    )
}

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
}

@Database(entities = [PhotoEntity::class], version = 1, exportSchema = false)
abstract class RawlineDb : RoomDatabase() {
    abstract fun photos(): PhotoDao

    companion object {
        fun create(context: Context): RawlineDb =
            Room.databaseBuilder(context, RawlineDb::class.java, "rawline.db").fallbackToDestructiveMigration(true).build()
    }
}
