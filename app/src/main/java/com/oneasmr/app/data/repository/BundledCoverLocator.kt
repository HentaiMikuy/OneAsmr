package com.oneasmr.app.data.repository

import android.content.Context
import com.oneasmr.app.data.scanner.FsPath
import com.oneasmr.app.data.scanner.SafDocumentFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pre-scrape cover fallback (plan Task 10): covers bundled inside the work
 * folder itself (common names cover.jpg / folder.jpg / 封面*). Abstracted so
 * CoverStore is JVM-testable (fake counts lookups; the per-work result cache
 * is asserted without any Android runtime).
 */
interface BundledCoverLocator {
    /**
     * Content:// uri of the first cover-named file inside the work folder
     * ([rootFolderUri] SAF tree + [relativeDir] path), or null. May throw on
     * unreadable folders / lost grants — CoverStore swallows and caches null.
     * Callers MUST cache the result per work (list-scroll IO rule).
     */
    suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String?
}

/**
 * Production locator over SAF. Reuses [SafDocumentFs] — the project's ONLY
 * traversal path (Task 6 learning: direct DocumentsContract queries, one IPC
 * per directory, never DocumentFile.listFiles()). Descent: tree root →
 * relativeDir segments by display name → first cover-named child.
 */
class SafBundledCoverLocator(context: Context) : BundledCoverLocator {

    private val appContext = context.applicationContext

    override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? =
        withContext(Dispatchers.IO) {
            val fs = SafDocumentFs(appContext, rootFolderUri)
            var path = FsPath(emptyList())
            for (segment in relativeDir.split('/').filter { it.isNotBlank() }) {
                val child = fs.listChildren(path).firstOrNull { it.name == segment && it.isDirectory }
                    ?: return@withContext null
                path = path + child.documentId
            }
            fs.listChildren(path).firstOrNull { isBundledCoverName(it.name) }?.documentUri
        }
}

/**
 * Matches the plan's bundled-cover naming set, case-insensitively:
 * `cover.<ext>`, `folder.<ext>` (any extension), `封面*` (Chinese prefix, any
 * extension). A bare "cover" without extension is NOT a cover (matches
 * kikoeru file conventions, avoids false positives on dirs named "cover").
 * Pure function — unit-tested.
 */
fun isBundledCoverName(name: String): Boolean {
    val n = name.trim().lowercase()
    return n.startsWith("cover.") || n.startsWith("folder.") || n.startsWith("封面")
}
