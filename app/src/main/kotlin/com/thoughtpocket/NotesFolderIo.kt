package com.thoughtpocket

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * SAF file operations for the notes sync folder (a user-picked tree, typically a Nextcloud-synced dir):
 * list `.md` files with their modified-time, read/create/overwrite, and move-to-`.trash` (the tombstone
 * for a propagated delete). All best-effort — failures are logged, never thrown (the DB stays the source
 * of truth; a failed file op just retries next reconcile).
 */
object NotesFolderIo {
    private const val TAG = "NotesFolderIo"
    const val TRASH = ".trash"
    private const val MIME = "text/markdown"

    data class DocFile(val uri: Uri, val name: String, val mtime: Long)

    private fun parentDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun listIn(context: Context, tree: Uri, parentDocId: String): List<DocFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<DocFile>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".md")) continue
                    out.add(DocFile(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2)))
                }
            }
        }.onFailure { Log.w(TAG, "list failed", it) }
        return out
    }

    /** `.md` files directly under the sync folder root. */
    fun list(context: Context, tree: Uri): List<DocFile> =
        listIn(context, tree, DocumentsContract.getTreeDocumentId(tree))

    /** `.md` files under the `.trash` subfolder (tombstones for deleted notes). */
    fun listTrash(context: Context, tree: Uri): List<DocFile> {
        val trash = findChildDir(context, tree, TRASH) ?: return emptyList()
        return listIn(context, tree, DocumentsContract.getDocumentId(trash))
    }

    fun read(context: Context, uri: Uri): String? =
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .onFailure { Log.w(TAG, "read failed", it) }.getOrNull()

    fun create(context: Context, tree: Uri, name: String, content: String): Uri? =
        runCatching {
            val doc = DocumentsContract.createDocument(context.contentResolver, parentDoc(tree), MIME, name) ?: return null
            write(context, doc, content); doc
        }.onFailure { Log.w(TAG, "create failed", it) }.getOrNull()

    fun overwrite(context: Context, uri: Uri, content: String): Boolean =
        runCatching { write(context, uri, content); true }.onFailure { Log.w(TAG, "overwrite failed", it) }.getOrDefault(false)

    private fun write(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)

    /** Move [file] into `.trash` (created on demand): copy its content there, then delete the original. */
    fun moveToTrash(context: Context, tree: Uri, file: DocFile): Boolean =
        runCatching {
            val content = read(context, file.uri) ?: return false
            val trash = findOrCreateDir(context, tree, TRASH) ?: return false
            val doc = DocumentsContract.createDocument(context.contentResolver, trash, MIME, file.name) ?: return false
            write(context, doc, content)
            delete(context, file.uri)
            true
        }.onFailure { Log.w(TAG, "moveToTrash failed", it) }.getOrDefault(false)

    private fun findChildDir(context: Context, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && c.getString(1) == name)
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    private fun findOrCreateDir(context: Context, tree: Uri, name: String): Uri? =
        findChildDir(context, tree, name)
            ?: runCatching {
                DocumentsContract.createDocument(context.contentResolver, parentDoc(tree), DocumentsContract.Document.MIME_TYPE_DIR, name)
            }.getOrNull()
}
