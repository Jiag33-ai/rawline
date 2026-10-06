package app.rawline.feature.studio

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.rawline.core.studio.model.ProjectRow

/** One row of the Studio home index. Everything here can be rebuilt from the project directories (spec 3.6). */
@Entity(tableName = "projects")
data class StudioProjectEntity(@PrimaryKey val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val layerCount: Int, val sizeBytes: Long, val hasThumb: Boolean)

@Dao
interface StudioProjectDao {
    @Query("SELECT * FROM projects ORDER BY modified DESC, id") suspend fun all(): List<StudioProjectEntity>
    @Upsert suspend fun upsert(rows: List<StudioProjectEntity>)
    @Query("DELETE FROM projects WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
}

/**
 * studio.db: a rebuildable index, not the source of truth. A schema change or a file that will not open just rebuilds it from the directories, so this is the one database in the
 * app with a destructive fallback (decision D3; Develop's database never wipes). Not part of Develop's backup.
 */
@Database(entities = [StudioProjectEntity::class], version = 1, exportSchema = true)
abstract class StudioDb : RoomDatabase() {
    abstract fun projects(): StudioProjectDao

    companion object {
        @Volatile private var inst: StudioDb? = null
        fun get(c: Context): StudioDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, StudioDb::class.java, "studio.db").fallbackToDestructiveMigration(dropAllTables = true).build().also { inst = it }
        }
    }
}

fun StudioProjectEntity.toRow() = ProjectRow(id, name, width, height, modified, layerCount, sizeBytes, hasThumb)
fun ProjectRow.toEntity() = StudioProjectEntity(id, name, width, height, modified, layerCount, sizeBytes, hasThumb)
