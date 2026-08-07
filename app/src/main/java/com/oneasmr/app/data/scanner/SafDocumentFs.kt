package com.oneasmr.app.data.scanner

import android.content.Context
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Production [DocumentFs] over SAF's DocumentsContract (plan Task 6).
 *
 * THIS IS THE ONLY SAF traversal path: children are listed with
 * `DocumentsContract.buildChildDocumentsUriUsingTree` + a direct
 * `contentResolver.query` over the EXACT projection
 * document_id / display_name / mime_type / last_modified / size — one IPC per
 * directory instead of `DocumentFile.listFiles()`'s per-node IPC (which
 * stalls for tens of minutes on trees of ten-thousands of files).
 *
 * One instance is bound to ONE tree root URI (the scanner treats every root
 * separately, so paths are always relative to that tree).
 */
class SafDocumentFs(context: Context, private val treeUri: String) : DocumentFs {

    private val resolver = context.contentResolver
    private val treeDocumentId: String = DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))

    override fun listChildren(path: FsPath): List<FsEntry> = try {
        val parentDocumentId = if (path.isEmpty) treeDocumentId else path.last
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            android.net.Uri.parse(treeUri),
            parentDocumentId,
        )
        resolver.query(childrenUri, PROJECTION, null, null, null)
            ?.use { cursor -> cursorToEntries(cursor) }
            ?: throw DocumentReadException("query returned null for $path")
    } catch (e: DocumentReadException) {
        throw e
    } catch (e: Exception) {
        throw DocumentReadException("cannot list $path under $treeUri: ${e.message}", e)
    }

    private fun cursorToEntries(cursor: Cursor): List<FsEntry> {
        val idIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
        val modifiedIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
        val sizeIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)
        val entries = mutableListOf<FsEntry>()
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(idIdx)
            if (documentId.isNullOrBlank()) continue
            val mime = cursor.getString(mimeIdx)
            entries += FsEntry(
                name = cursor.getString(nameIdx) ?: documentId,
                documentId = documentId,
                documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    android.net.Uri.parse(treeUri),
                    documentId,
                ).toString(),
                isDirectory = mime == Document.MIME_TYPE_DIR,
                lastModified = if (cursor.isNull(modifiedIdx)) 0L else cursor.getLong(modifiedIdx),
                size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx),
            )
        }
        return entries
    }

    companion object {
        private const val TAG = "OneAsmrSafFs"

        /**
         * Exact projection mandated by plan Task 6 — nothing more (each column
         * costs provider-side work at scale).
         */
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}
