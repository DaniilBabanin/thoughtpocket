package com.thoughtpocket.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One "Code this" task on a note. Iterating on an item (follow-up or an
 * edited-code rerun) updates the row in place; a fresh prompt from the note
 * creates a new row. [originalCode] never changes after the first success —
 * it's what "revert" restores after hand edits.
 */
@Entity(
    tableName = "code_runs",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class CodeRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val createdAt: Long,
    /** Latest instruction; follow-ups append as an " → " chain for the preview. */
    val instruction: String,
    val code: String,
    val originalCode: String,
    val output: String,
    val attempts: Int,
)

@Dao
interface CodeRunDao {
    @Query("SELECT * FROM code_runs WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun byNote(noteId: Long): Flow<List<CodeRun>>

    @Query("SELECT * FROM code_runs WHERE id = :id")
    suspend fun getById(id: Long): CodeRun?

    @Insert
    suspend fun insert(run: CodeRun): Long

    @Update
    suspend fun update(run: CodeRun)

    @Query("DELETE FROM code_runs WHERE id = :id")
    suspend fun delete(id: Long)
}
