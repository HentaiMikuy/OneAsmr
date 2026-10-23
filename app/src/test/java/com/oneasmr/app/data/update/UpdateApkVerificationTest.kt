package com.oneasmr.app.data.update

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateApkVerificationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val info = UpdateInfo("99.0.0", "https://example.com/app.apk", 3, "说明")
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `file is accepted when size and supplied digest match`() = runBlocking {
        val file = tmp.newFile().apply { writeText("abc") }
        verifyUpdateApk(file, info.copy(sha256 = abcSha256.uppercase()))
    }

    @Test(expected = IOException::class)
    fun `same size corrupted payload is rejected by digest`() = runBlocking {
        val file = tmp.newFile().apply { writeText("abd") }
        verifyUpdateApk(file, info.copy(sha256 = abcSha256))
    }

    @Test(expected = IOException::class)
    fun `empty response is rejected even when release size is unknown`() = runBlocking {
        verifyUpdateApk(tmp.newFile(), info.copy(sizeBytes = 0))
    }

    @Test(expected = IOException::class)
    fun `truncated payload is rejected without a digest`() = runBlocking {
        val file = tmp.newFile().apply { writeText("ab") }
        verifyUpdateApk(file, info)
    }

    @Test
    fun `older releases without digest still support size verification`() = runBlocking {
        verifyUpdateApk(tmp.newFile().apply { writeText("abc") }, info)
    }
}
