package com.boilthis.raybandisplaydemo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class GlassTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val status: String = "PENDING",
    val results: String? = null,
    val evidencePath: String? = null,
    val priority: Int = 1 // 1: Low, 2: Medium, 3: High
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id ASC")
    fun getAllTasks(): Flow<List<GlassTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: GlassTaskEntity)

    @Update
    suspend fun updateTask(task: GlassTaskEntity)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Delete
    suspend fun deleteTask(task: GlassTaskEntity)
}

@Database(entities = [GlassTaskEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "glass_tasks_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
