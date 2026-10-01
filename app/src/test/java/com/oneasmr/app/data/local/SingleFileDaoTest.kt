package com.oneasmr.app.data.local

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 单文件库 + 收藏夹 DAO 行为:系统夹播种幂等/守卫、成员级联、REPLACE 禁用
 * 后的 insert/update 语义。同 OneAsmrDatabaseTest 的 Robolectric 内存库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleFileDaoTest {

    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(path: String, title: String = path) = SingleFile(
        rootFolderUri = "content://tree/singles",
        relativePath = path,
        fileName = path.substringAfterLast('/'),
        displayTitle = title,
        titleSortKey = SortKeyGenerator.generate(title),
        kind = SingleFileKind.VIDEO,
        youtubeId = null,
        channel = null,
        uploadDate = null,
        durationMs = null,
        sizeBytes = 1L,
        lastModified = 0L,
        thumbSource = null,
        sourceUrl = null,
        scrapeStatus = ScrapeStatus.NOT_SCRAPED,
        missing = false,
        addedAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `insert ignores duplicate location and returns -1`() = runBlocking {
        val first = db.singleFileDao().insert(file("a.mp4"))
        assertTrue(first > 0)
        assertEquals(-1L, db.singleFileDao().insert(file("a.mp4", title = "changed")))
        // IGNORE 未覆盖原行。
        assertEquals("a.mp4", db.singleFileDao().getById(first)!!.displayTitle)
    }

    @Test
    fun `update keeps collection membership intact`() = runBlocking {
        val dao = db.singleFileDao()
        val id = dao.insert(file("a.mp4"))
        db.collectionDao().ensureFavorites(now = 1_000L)
        db.collectionDao().addItem(CollectionItem(Collection.FAVORITES_ID, id, 1, 1_000L))

        val row = dao.getById(id)!!
        dao.update(row.copy(displayTitle = "new title", updatedAt = 2_000L))

        assertEquals("new title", dao.getById(id)!!.displayTitle)
        // @Update 原地改行,不触发 REPLACE 的删-插级联:成员关系存活。
        assertEquals(listOf(Collection.FAVORITES_ID), db.collectionDao().membershipOf(id))
    }

    @Test
    fun `deleting a file cascades its memberships`() = runBlocking {
        val id = db.singleFileDao().insert(file("a.mp4"))
        db.collectionDao().ensureFavorites(now = 1_000L)
        db.collectionDao().addItem(CollectionItem(Collection.FAVORITES_ID, id, 1, 1_000L))

        db.singleFileDao().deleteById(id)

        assertEquals(0, db.collectionDao().collectionsWithCountFlow().first().single().fileCount)
    }

    @Test
    fun `ensureFavorites is idempotent and favorites resists rename and delete`() = runBlocking {
        val dao = db.collectionDao()
        dao.ensureFavorites(now = 1_000L)
        dao.ensureFavorites(now = 9_999L)
        val favorites = dao.getById(Collection.FAVORITES_ID)!!
        assertEquals(1_000L, favorites.createdAt) // 第二次播种被 IGNORE
        assertTrue(favorites.isSystem)

        dao.rename(Collection.FAVORITES_ID, "hacked")
        dao.delete(Collection.FAVORITES_ID)
        val after = dao.getById(Collection.FAVORITES_ID)
        assertNotNull(after) // isSystem=0 守卫:改名/删除都拦下
        assertEquals("收藏", after!!.name)
    }

    @Test
    fun `custom collection lifecycle create rename delete`() = runBlocking {
        val dao = db.collectionDao()
        dao.ensureFavorites(now = 1_000L)
        val id = dao.insert(Collection(name = "助眠", isSystem = false, sortIndex = 1, createdAt = 2_000L))
        assertTrue(id > Collection.FAVORITES_ID)

        dao.rename(id, "助眠向け")
        assertEquals("助眠向け", dao.getById(id)!!.name)

        val fileId = db.singleFileDao().insert(file("a.mp4"))
        dao.addItem(CollectionItem(id, fileId, 1, 2_000L))
        dao.delete(id)
        assertNull(dao.getById(id))
        // 夹删除级联清成员,文件行本身不动。
        assertEquals(emptyList<Long>(), dao.membershipOf(fileId))
        assertNotNull(db.singleFileDao().getById(fileId))
    }

    @Test
    fun `files in collection follow item sort order`() = runBlocking {
        val cd = db.collectionDao()
        cd.ensureFavorites(now = 1_000L)
        val a = db.singleFileDao().insert(file("a.mp4"))
        val b = db.singleFileDao().insert(file("b.mp4"))
        cd.addItem(CollectionItem(Collection.FAVORITES_ID, b, sortIndex = 1, addedAt = 1_000L))
        cd.addItem(CollectionItem(Collection.FAVORITES_ID, a, sortIndex = 2, addedAt = 2_000L))

        val files = cd.filesInCollectionFlow(Collection.FAVORITES_ID).first()
        assertEquals(listOf(b, a), files.map { it.id })
    }

    @Test
    fun `listByRoot scopes to one root, skips missing, orders by titleSortKey`() = runBlocking {
        val dao = db.singleFileDao()
        val rootA = "content://tree/singles"
        val rootB = "content://tree/other"
        // 插入顺序刻意非排序顺序:验证结果按 titleSortKey,而非 rowId/插入序。
        dao.insert(file("b.mp4", title = "Beta"))
        dao.insert(file("c.mp4", title = "Gamma").copy(rootFolderUri = rootB))
        dao.insert(file("a.mp4", title = "Alpha"))
        val missingId = dao.insert(file("z.mp4", title = "Zero"))
        dao.markMissing(listOf(missingId), now = 2_000L)

        val aRows = dao.listByRoot(rootA).map { it.displayTitle }
        val bRows = dao.listByRoot(rootB).map { it.displayTitle }

        assertEquals(listOf("Alpha", "Beta"), aRows)
        assertEquals(listOf("Gamma"), bRows)
    }

    @Test
    fun `markMissing flags rows and empty list is a no-op`() = runBlocking {
        val dao = db.singleFileDao()
        val id = dao.insert(file("a.mp4"))
        dao.markMissing(emptyList(), now = 2_000L)
        assertFalse(dao.getById(id)!!.missing)
        dao.markMissing(listOf(id), now = 2_000L)
        assertTrue(dao.getById(id)!!.missing)
        assertEquals(2_000L, dao.getById(id)!!.updatedAt)
    }
}

/** 测试便捷:一个文件的所属夹 id 列表(生产走 allItemsFlow 派生)。 */
private suspend fun CollectionDao.membershipOf(fileId: Long): List<Long> =
    allItemsFlow().first().filter { it.fileId == fileId }.map { it.collectionId }
