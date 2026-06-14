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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val text: String,
    val title: String = "",
    // LLM-formatted Markdown (lists/checklists/prose); "" until generated. [text] stays the raw transcript.
    val markdown: String = "",
    val tags: List<String> = emptyList(),
    // Semantic embedding (Universal Sentence Encoder); null until computed.
    val embedding: FloatArray? = null,
)

/** Tags stored as a single delimited column; embedding as a little-endian float BLOB. */
class Converters {
    @TypeConverter
    fun fromTags(tags: List<String>): String = tags.joinToString(SEP)

    @TypeConverter
    fun toTags(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split(SEP)

    @TypeConverter
    fun fromVec(v: FloatArray?): ByteArray? {
        if (v == null) return null
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    @TypeConverter
    fun toVec(b: ByteArray?): FloatArray? {
        if (b == null) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }

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

@Database(entities = [Note::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NotesDb : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        @Volatile private var instance: NotesDb? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN embedding BLOB")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN markdown TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotesDb::class.java, "notes.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
