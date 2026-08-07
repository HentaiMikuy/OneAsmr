package com.oneasmr.app.data.scanner

/**
 * In-memory fake filesystem for scanner / track-tree tests (plan Task 6 QA:
 * "happy = 假文件系统 3 个作品 40 文件全部正确归类排序").
 *
 * Document ids equal display names (names are unique per parent in every
 * fixture — legal in a fake; real SAF document ids are opaque strings that
 * the core never inspects). Paths are name paths: root = emptyList().
 *
 * A fresh instance per test is a fresh filesystem (stale_state hygiene —
 * never share state across tests).
 */
class FakeDocumentFs : DocumentFs {

    private val childrenOf = mutableMapOf<List<String>, MutableList<FsEntry>>()
    private val unreadable = mutableSetOf<List<String>>()

    /** Adds a directory under [parent], returns its name path. */
    fun addDirectory(parent: List<String> = emptyList(), name: String): List<String> {
        val path = parent + name
        addEntry(parent, entry(path, isDirectory = true, size = 0L))
        return path
    }

    /** Adds a file under [parent], returns its name path. */
    fun addFile(parent: List<String> = emptyList(), name: String, size: Long = 0L): List<String> {
        val path = parent + name
        addEntry(parent, entry(path, isDirectory = false, size = size))
        return path
    }

    /** Makes [path] throw [DocumentReadException] from [listChildren] (unreadable dir). */
    fun markUnreadable(path: List<String>) {
        unreadable += path
    }

    /** Children of [parent] as stored (insertion order; scanners sort themselves). */
    fun entriesUnder(parent: List<String>): List<FsEntry> =
        childrenOf[parent]?.toList() ?: emptyList()

    override fun listChildren(path: FsPath): List<FsEntry> {
        if (path.segments in unreadable) {
            throw DocumentReadException("permission denied: ${path.segments.joinToString("/")}")
        }
        return childrenOf[path.segments]?.toList() ?: emptyList()
    }

    private fun addEntry(parent: List<String>, e: FsEntry) {
        childrenOf.getOrPut(parent) { mutableListOf() }.add(e)
    }

    private fun entry(path: List<String>, isDirectory: Boolean, size: Long): FsEntry =
        FsEntry(
            name = path.last(),
            documentId = path.last(),
            documentUri = "content://fake/${path.joinToString("/")}",
            isDirectory = isDirectory,
            lastModified = 0L,
            size = size,
        )
}
