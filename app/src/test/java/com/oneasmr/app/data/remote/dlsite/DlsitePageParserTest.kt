package com.oneasmr.app.data.remote.dlsite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven spec tests for [DlsitePageParser] (plan Task 9, static fields).
 *
 * Fixtures are trimmed-but-realistic captures of live DLsite work pages
 * (fetched 2026-08-07 through the local proxy, zh-cn locale cookie):
 * - RJ263959: CG work, series + R18 + 3 genre tags, NO VA row (CG works have none)
 * - RJ283598: manga, series + 全年龄 (nsfw=false) + 7 genre tags, NO VA row
 * - RJ327447: voice/ASMR work, series + R18 + 8 genre tags + 声优 (VA) row
 *
 * Every asserted value below was cross-checked against the live page and the
 * kikoeru scraper/dlsite.js parse strategy (title og:title strip, maker_name,
 * work_outline row-by-label lookup, genre regex).
 */
class DlsitePageParserTest {

    private fun fixtureHtml(rj: String): String =
        checkNotNull(javaClass.classLoader.getResource("dlsite/$rj.html")) { "missing fixture $rj.html" }
            .readText()

    // --- RJ263959 (CG, zh-cn, R18, series, no VA) ---

    @Test
    fun `RJ263959 parses title circle nsfw release series`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ263959"), DlsiteLanguage.ZH_CN)
        assertEquals("マギレコ全員H 第四弾", w.title)
        assertEquals("芋焼酎", w.circle)
        assertTrue(w.nsfw)
        assertEquals("2019-09-06", w.releaseDate)
        assertEquals("マギレコ全員H", w.seriesName)
    }

    @Test
    fun `RJ263959 parses genre tags in order`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ263959"), DlsiteLanguage.ZH_CN)
        assertEquals(listOf("内射/中出", "外射", "母乳"), w.tags)
    }

    @Test
    fun `RJ263959 has no va row - vas empty`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ263959"), DlsiteLanguage.ZH_CN)
        assertTrue(w.vas.isEmpty())
    }

    @Test
    fun `RJ263959 cover urls main sam 240x240 360x360`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ263959"), DlsiteLanguage.ZH_CN)
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ264000/RJ263959_img_main.jpg",
            w.covers.main,
        )
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ264000/RJ263959_img_smp1.jpg",
            w.covers.sam,
        )
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ264000/RJ263959_img_main_240x240.jpg",
            w.covers.thumb240,
        )
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ264000/RJ263959_img_main_360x360.jpg",
            w.covers.thumb360,
        )
    }

    // --- RJ283598 (manga, zh-cn, all-ages, series, no VA) ---

    @Test
    fun `RJ283598 parses all-ages work - nsfw false`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ283598"), DlsiteLanguage.ZH_CN)
        assertEquals("艦隊ジャーナル総集編 Sequence 5", w.title)
        assertEquals("Check Mate!", w.circle)
        assertFalse(w.nsfw)
        assertEquals("2020-05-03", w.releaseDate)
        assertEquals("艦隊ジャーナル", w.seriesName)
        assertTrue(w.vas.isEmpty())
    }

    @Test
    fun `RJ283598 parses seven genre tags`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ283598"), DlsiteLanguage.ZH_CN)
        assertEquals(
            listOf("系列作品", "总集篇", "喜剧", "严肃/沉重", "军事", "疯狂", "血液/流血"),
            w.tags,
        )
    }

    // --- RJ327447 (voice/ASMR, zh-cn, R18, series, VA row) ---

    @Test
    fun `RJ327447 parses voice work with va row`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ327447"), DlsiteLanguage.ZH_CN)
        assertEquals(
            "本当に深い耳奥\"ピストン\"舐め ～お耳の奥に舌を抜き差しされる≪深層挿入ASMR≫～",
            w.title,
        )
        assertEquals("パステル×トリップ", w.circle)
        assertTrue(w.nsfw)
        assertEquals("2021-05-21", w.releaseDate)
        assertEquals("深層ASMR", w.seriesName)
        assertEquals(listOf("みもりあいの"), w.vas)
    }

    @Test
    fun `RJ327447 parses eight genre tags`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ327447"), DlsiteLanguage.ZH_CN)
        assertEquals(
            listOf("ASMR", "双声道立体声/人头麦", "姐姐", "亲热/甜蜜", "低语", "内射/中出", "舔耳", "处女"),
            w.tags,
        )
    }

    @Test
    fun `RJ327447 cover urls`() {
        val w = DlsitePageParser.parseWorkPage(fixtureHtml("RJ327447"), DlsiteLanguage.ZH_CN)
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ328000/RJ327447_img_main.jpg",
            w.covers.main,
        )
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ328000/RJ327447_img_smp1.jpg",
            w.covers.sam,
        )
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ328000/RJ327447_img_main_240x240.jpg",
            w.covers.thumb240,
        )
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ328000/RJ327447_img_main_360x360.jpg",
            w.covers.thumb360,
        )
    }

    // --- ja-jp label table (hand-written fixture modeled on the live structure) ---

    @Test
    fun `ja-jp labels parse with japanese keywords`() {
        // Hand-written minimal fixture: same table structure as the live zh-cn
        // pages, but with ja-jp row labels (販売日/シリーズ名/年齢指定/ジャンル/声優),
        // the locale kikoeru's dlsite.js maps to cookie locale=ja-jp.
        val html = """
            <!DOCTYPE html><html lang="ja"><head>
            <meta property="og:title" content="サンプル作品 [テストサークル] | DLsite">
            </head><body>
            <div id="work_header">
            <table id="work_maker">
              <tr><th>サークル名</th><td>
                <span itemprop="brand" class="maker_name"><a href="https://www.dlsite.com/maniax/circle/profile/=/maker_id/RG99999.html">テストサークル</a></span>
              </td></tr>
            </table>
            <table cellspacing="0" id="work_outline">
              <tr><th>販売日</th><td><a href="https://www.dlsite.com/maniax/new/">2024年01月02日</a></td></tr>
              <tr><th>シリーズ名</th><td><a href="https://www.dlsite.com/maniax/fsr/=/title_id/SRI0000000001/order/title_d">シリーズX</a></td></tr>
              <tr><th>年齢指定</th><td><div class="work_genre"><span class="icon_ADL" title="R18">R18</span></div></td></tr>
              <tr><th>ジャンル</th><td><div class="main_genre">
                <a href="https://www.dlsite.com/maniax/fsr/=/genre/010/from/work.genre">日常</a>
                <a href="https://www.dlsite.com/maniax/fsr/=/genre/011/from/work.genre">恋人</a>
              </div></td></tr>
              <tr><th>声優</th><td><a href="https://www.dlsite.com/maniax/fsr/=/keyword_creater">声優A</a></td></tr>
            </table>
            </div></body></html>
        """.trimIndent()
        val w = DlsitePageParser.parseWorkPage(html, DlsiteLanguage.JA_JP)
        assertEquals("サンプル作品", w.title)
        assertEquals("テストサークル", w.circle)
        assertTrue(w.nsfw)
        assertEquals("2024-01-02", w.releaseDate)
        assertEquals("シリーズX", w.seriesName)
        assertEquals(listOf("日常", "恋人"), w.tags)
        assertEquals(listOf("声優A"), w.vas)
    }

    // --- failure paths ---

    @Test(expected = DlsiteScrapeException::class)
    fun `corrupted html without og title throws parse error`() {
        DlsitePageParser.parseWorkPage("<html><body>not a dlsite page</body></html>", DlsiteLanguage.ZH_CN)
    }

    @Test(expected = DlsiteScrapeException::class)
    fun `empty html throws parse error`() {
        DlsitePageParser.parseWorkPage("", DlsiteLanguage.ZH_CN)
    }

    @Test(expected = DlsiteScrapeException::class)
    fun `work page without outline table throws parse error`() {
        val html = """
            <!DOCTYPE html><html><head>
            <meta property="og:title" content="タイトル [サークル] | DLsite">
            </head><body><div id="work_header"></div></body></html>
        """.trimIndent()
        // No #work_outline: tags/vas unreachable -> structural change -> parse error
        DlsitePageParser.parseWorkPage(html, DlsiteLanguage.ZH_CN)
    }

    @Test
    fun `missing optional fields stay null not crash`() {
        val html = """
            <!DOCTYPE html><html lang="zh-CN"><head>
            <meta property="og:title" content="作品 [サークル] | DLsite">
            <meta property="og:image" content="https://img.dlsite.jp/modpub/images2/work/doujin/RJ000000/RJ999999_img_main.jpg">
            </head><body><div id="work_header">
            <table id="work_maker">
              <tr><th>社团名</th><td><span itemprop="brand" class="maker_name"><a href="https://www.dlsite.com/maniax/circle/profile/=/maker_id/RG1.html">サークル</a></span></td></tr>
            </table>
            <table cellspacing="0" id="work_outline">
              <tr><th>发售日</th><td>2020年01月01日</td></tr>
              <tr><th>年龄指定</th><td><div class="work_genre"><span class="icon_GEN">全年龄</span></div></td></tr>
              <tr><th>分类</th><td><div class="main_genre"><a href="https://www.dlsite.com/maniax/fsr/=/genre/010/from/work.genre">日常</a></div></td></tr>
            </table>
            </div></body></html>
        """.trimIndent()
        val w = DlsitePageParser.parseWorkPage(html, DlsiteLanguage.ZH_CN)
        assertEquals("作品", w.title)
        assertNull(w.seriesName) // no 系列名 row
        assertFalse(w.nsfw) // 全年龄, not R18
        assertNull(w.covers.sam) // no slider data
        assertNull(w.covers.thumb240)
        // 360x360 is derived from main whenever main exists (no slider needed)
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ000000/RJ999999_img_main_360x360.jpg",
            w.covers.thumb360,
        )
    }
}
