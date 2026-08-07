package com.oneasmr.app.data.remote.dlsite

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses static metadata from a DLsite work page HTML document.
 *
 * Selector sources (each documented at its use site):
 * - kikoeru-express scraper/dlsite.js @ dd030f3: title from og:title with the
 *   ` [circle] | DLsite` suffix stripped, circle from `span.maker_name a`,
 *   outline rows looked up by `th` label text, genre tags filtered by the
 *   `/genre/(\d{3})` href pattern, VA row by label.
 * - current DLsite structure observed live 2026-08-07: rows are direct `tr`
 *   children of `#work_outline` (no tbody wrapper anymore; kikoeru's tbody
 *   path is dead), zh-cn labels are 发售日/系列名/年龄指定/分类/声优, cover URLs
 *   come from `meta[itemprop=image]` (main), the product-slider `data-src` /
 *   `data-thumb` attributes (sam + 240x240) and are derived for 360x360.
 */
object DlsitePageParser {

    /** zh-cn / zh-tw / ja-jp labels per field (kikoeru dlsite.js label map + live zh-cn pages). */
    private val RELEASE_LABELS = setOf("贩卖日", "发售日", "販売日", "販賣日")
    private val SERIES_LABELS = setOf("系列名", "シリーズ名")
    private val AGE_LABELS = setOf("年龄指定", "年齢指定", "年齡指定")
    private val GENRE_LABELS = setOf("分类", "ジャンル", "分類")
    private val VA_LABELS = setOf("声优", "声優", "聲優")

    /** `{title} [circle] | DLsite` -> `{title}` (kikoeru titlePattern). */
    private val TITLE_SUFFIX = Regex("\\s*\\[.+\\]\\s*\\|\\s*DLsite.*$")

    /**
     * Parses [html] and returns the static fields. Throws [DlsiteScrapeException]
     * with [DlsiteScrapeException.Kind.PARSE_ERROR] when the page structure no
     * longer exposes the required selectors (title, circle or the outline table).
     */
    fun parseWorkPage(html: String, language: DlsiteLanguage): WorkPageFields {
        val doc = Jsoup.parse(html)
        val title = parseTitle(doc)
            ?: throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "no og:title on work page")
        val circle = parseCircle(doc)
        val outline = doc.selectFirst("#work_outline")
            ?: throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "no #work_outline table (page structure changed)")
        val nsfw = parseRowValue(outline, AGE_LABELS)?.let { rowValue ->
            rowValue.contains("R18") || rowValue.contains("18禁")
        } ?: false
        val release = parseRowValue(outline, RELEASE_LABELS)?.let { toIsoDate(it) }
        val series = parseRowValue(outline, SERIES_LABELS)?.takeIf { it.isNotBlank() }
        val tags = parseRowLinks(outline, GENRE_LABELS).map { it.text().trim() }
        val vas = parseRowLinks(outline, VA_LABELS).map { it.text().trim() }
        val covers = parseCovers(doc, outline)
        return WorkPageFields(title, circle, nsfw, release, series, tags, vas, covers)
    }

    private fun parseTitle(doc: Document): String? {
        val raw = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val cleaned = TITLE_SUFFIX.replace(raw, "")
        return cleaned.ifBlank { null }
    }

    private fun parseCircle(doc: Document): String? =
        doc.selectFirst("span.maker_name a")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseRowValue(outline: Element, labels: Set<String>): String? {
        val row = rowWithLabel(outline, labels) ?: return null
        return row.select("td").text().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseRowLinks(outline: Element, labels: Set<String>): List<Element> {
        val row = rowWithLabel(outline, labels) ?: return emptyList()
        return row.select("td a").filter { it.hasAttr("href") }
    }

    private fun rowWithLabel(outline: Element, labels: Set<String>): Element? =
        outline.select("tr").firstOrNull { row ->
            labels.contains(row.selectFirst("th")?.text()?.trim())
        }

    /** "2019年09月06日" -> "2019-09-06"; null when fewer than 8 digits. */
    private fun toIsoDate(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 8) return null
        return "${digits.take(4)}-${digits.drop(4).take(2)}-${digits.drop(6).take(2)}"
    }

    private fun parseCovers(doc: Document, outline: Element): DlsiteCovers {
        val mainRaw = doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: outline.selectFirst("img[src*=_img_main]")?.attr("src")
        val slider = doc.selectFirst(".product-slider-data")
        val srcs = slider?.select("div[data-src]")?.map { it.attr("data-src") } ?: emptyList()
        val samRaw = srcs.firstOrNull { "_img_smp".let { s -> it.contains(s) } }
        val thumb240Raw = slider?.select("div[data-thumb]")?.map { it.attr("data-thumb") }
            ?.firstOrNull { it.contains("_img_main_240x240") }
        return DlsiteCovers(
            main = mainRaw?.toHttps(),
            sam = samRaw?.toHttps(),
            thumb240 = thumb240Raw?.toHttps(),
            thumb360 = mainRaw?.let { derive360(it) },
        )
    }

    /**
     * 360x360 is not present in the page; it is derived from the main image
     * with the same rule kikoeru's cover route uses (img.dlsite.jp /resize/
     * path + `_img_main_360x360.jpg` suffix, verified live 2026-08-07).
     */
    private fun derive360(main: String): String {
        val https = main.toHttps()
        val resized = https.replace("/modpub/", "/resize/")
        return resized.replace("_img_main.jpg", "_img_main_360x360.jpg")
    }

    /** Protocol-relative (`//img.dlsite.jp/...`) -> https URL. */
    private fun String.toHttps(): String = if (startsWith("//")) "https:$this" else this
}
