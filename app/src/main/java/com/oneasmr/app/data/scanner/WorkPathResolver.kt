package com.oneasmr.app.data.scanner

/**
 * Resolves a work's stored [Work.relativeDir] (a DISPLAY-NAME path, e.g.
 * "RJ123456/CD1") back into SAF document-ids ([FsPath]) at request time
 * (plan Task 14: the detail page must build the track tree of a work folder
 * from the database row alone).
 *
 * SAF addresses children by opaque provider document ids — never by display
 * name — so the resolver walks the tree via [DocumentFs.listChildren] and
 * matches one display name per level. Names are unique within a single
 * directory, so the first match per level is deterministic.
 *
 * Failure semantics (plan "missing-work handling"): a missing segment or an
 * unreadable directory is reported as [Result.NotFound] with a human-readable
 * reason — the caller (detail page) shows the invalid state + rescan entry
 * instead of a blank page. A NOT-found resolution never throws.
 */
class WorkPathResolver(private val fs: DocumentFs) {

    sealed interface Result {
        /**
         * The work folder was found: [path] is its document-id path, and
         * [documentUri]/[displayName] are the folder's own document uri and
         * name (the TrackTreeBuilder root node inputs).
         */
        data class Found(
            val path: FsPath,
            val documentUri: String,
            val displayName: String,
        ) : Result

        /** The work folder is absent/unreadable. [message] is user-presentable. */
        data class NotFound(val message: String) : Result
    }

    /**
     * @param relativeDir the stored work-relative display-name path
     *   ("a/b/c", no leading/trailing slash). Empty/blank → [Result.NotFound].
     */
    fun resolve(relativeDir: String): Result {
        val segments = relativeDir.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return Result.NotFound("作品路径为空（相对路径 '$relativeDir'）")
        }
        var path = FsPath(emptyList())
        for ((index, name) in segments.withIndex()) {
            val entries = try {
                fs.listChildren(path)
            } catch (e: DocumentReadException) {
                val where = if (path.isEmpty) "根目录" else "目录 '${path.segments.last()}'"
                return Result.NotFound("无法读取$where：${e.message}")
            }
            val match = entries.firstOrNull { it.name == name && it.isDirectory }
                ?: return Result.NotFound("找不到目录 '$name'（相对路径 '$relativeDir'）")
            path += match.documentId
            if (index == segments.lastIndex) {
                return Result.Found(path = path, documentUri = match.documentUri, displayName = match.name)
            }
        }
        return Result.NotFound("作品路径 '$relativeDir' 未解析")
    }
}
