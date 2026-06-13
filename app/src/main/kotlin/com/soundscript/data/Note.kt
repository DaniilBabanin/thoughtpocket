package com.soundscript.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val text: String,
    val tags: List<String> = emptyList(),
)

/** Tags stored as a single delimited column — simple and enough for LIKE filtering. */
class Converters {
    @TypeConverter
    fun fromTags(tags: List<String>): String = tags.joinToString(SEP)

    @TypeConverter
    fun toTags(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split(SEP)

    companion object {
        // Unit separator — never appears in user-typed tags.
        private const val SEP = "\u001F"
    }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun byId(id: Long): Flow<Note?>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NotesDb : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        @Volatile private var instance: NotesDb? = null

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotesDb::class.java, "notes.db"
            ).build().also { instance = it }
        }
    }
}
