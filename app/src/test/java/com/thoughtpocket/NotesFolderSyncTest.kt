package com.thoughtpocket

import com.thoughtpocket.NotesFolderSync.Action
import com.thoughtpocket.NotesFolderSync.State
import com.thoughtpocket.NotesFolderSync.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesFolderSyncTest {
    @Test fun newRemoteFileIsImported() =
        // file with no note AND never synced → authored on another device → import
        assertEquals(Action.IMPORT, decide(dbFingerprint = null, fileFingerprint = 7, fileMtime = 100, tombstoned = false, last = null))

    @Test fun deletedInAppFileIsTombstoned() =
        // file with no note but we'd synced it before → user deleted it in-app → tombstone (propagate delete)
        assertEquals(Action.TRASH_FILE, decide(dbFingerprint = null, fileFingerprint = 7, fileMtime = 100, tombstoned = false, last = State(7, 100)))

    @Test fun newLocalNoteIsExported() =
        assertEquals(Action.EXPORT, decide(dbFingerprint = 7, fileFingerprint = null, fileMtime = 0, tombstoned = false, last = null))

    @Test fun inSyncDoesNothing() =
        assertEquals(Action.NONE, decide(dbFingerprint = 5, fileFingerprint = 5, fileMtime = 100, tombstoned = false, last = State(5, 100)))

    @Test fun localEditIsExported() =
        assertEquals(Action.EXPORT, decide(dbFingerprint = 9, fileFingerprint = 5, fileMtime = 100, tombstoned = false, last = State(5, 100)))

    @Test fun remoteEditIsImported() =
        assertEquals(Action.IMPORT, decide(dbFingerprint = 5, fileFingerprint = 9, fileMtime = 200, tombstoned = false, last = State(5, 100)))

    @Test fun conflictLastWriteWins() {
        // both changed; file modified after our last write → file wins (import)
        assertEquals(Action.IMPORT, decide(dbFingerprint = 9, fileFingerprint = 8, fileMtime = 200, tombstoned = false, last = State(5, 100)))
        // both changed; file not newer than our last write → local wins (export)
        assertEquals(Action.EXPORT, decide(dbFingerprint = 9, fileFingerprint = 8, fileMtime = 100, tombstoned = false, last = State(5, 100)))
    }

    @Test fun tombstoneDeletesTheNote() =
        assertEquals(Action.DELETE_NOTE, decide(dbFingerprint = 5, fileFingerprint = null, fileMtime = 0, tombstoned = true, last = State(5, 100)))

    @Test fun tombstoneWithNoNoteIsNoop() =
        assertEquals(Action.NONE, decide(dbFingerprint = null, fileFingerprint = null, fileMtime = 0, tombstoned = true, last = null))

    @Test fun vanishedFileNeverDeletesTheNote() =
        // file gone, NO tombstone → re-export (a missing file must not lose a note)
        assertEquals(Action.EXPORT, decide(dbFingerprint = 5, fileFingerprint = null, fileMtime = 0, tombstoned = false, last = State(5, 100)))

    @Test fun nothingOnEitherSideIsNoop() =
        assertEquals(Action.NONE, decide(dbFingerprint = null, fileFingerprint = null, fileMtime = 0, tombstoned = false, last = null))
}
