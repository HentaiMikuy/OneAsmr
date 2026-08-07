package com.oneasmr.app.data.local

import com.github.promeg.pinyinhelper.Pinyin

/**
 * Generates `titleSortKey` / `nameSortKey` for CJK-aware ordering:
 * - Chinese characters -> pinyin via TinyPinyin (tone digits stripped so
 *   "中文" sorts as "zhongwen" and groups alphabetically).
 * - Japanese hiragana/katakana -> Hepburn romaji (lowercased).
 * - Everything else -> lowercased as-is (ASCII romaji/numbers; punctuation and
 *   spaces pass through unchanged).
 *
 * Pure JVM: no Android dependency, so the mapping is unit-testable on the JVM
 * (the tinypinyin-android-asset-lexicons are not loaded — base dictionary only,
 * polyphone city names may pick the default reading; acceptable for sorting).
 */
object SortKeyGenerator {

    /** kana (hiragana + katakana) -> Hepburn romaji. */
    private val KANA_TO_ROMAJI: Map<Char, String> = buildMap {
        // hiragana basic gojuon
        put('あ', "a"); put('い', "i"); put('う', "u"); put('え', "e"); put('お', "o")
        put('か', "ka"); put('き', "ki"); put('く', "ku"); put('け', "ke"); put('こ', "ko")
        put('さ', "sa"); put('し', "shi"); put('す', "su"); put('せ', "se"); put('そ', "so")
        put('た', "ta"); put('ち', "chi"); put('つ', "tsu"); put('て', "te"); put('と', "to")
        put('な', "na"); put('に', "ni"); put('ぬ', "nu"); put('ね', "ne"); put('の', "no")
        put('は', "ha"); put('ひ', "hi"); put('ふ', "fu"); put('へ', "he"); put('ほ', "ho")
        put('ま', "ma"); put('み', "mi"); put('む', "mu"); put('め', "me"); put('も', "mo")
        put('や', "ya"); put('ゆ', "yu"); put('よ', "yo")
        put('ら', "ra"); put('り', "ri"); put('る', "ru"); put('れ', "re"); put('ろ', "ro")
        put('わ', "wa"); put('を', "o"); put('ん', "n")
        // hiragana dakuten / handakuten
        put('が', "ga"); put('ぎ', "gi"); put('ぐ', "gu"); put('げ', "ge"); put('ご', "go")
        put('ざ', "za"); put('じ', "ji"); put('ず', "zu"); put('ぜ', "ze"); put('ぞ', "zo")
        put('だ', "da"); put('ぢ', "ji"); put('づ', "zu"); put('で', "de"); put('ど', "do")
        put('ば', "ba"); put('び', "bi"); put('ぶ', "bu"); put('べ', "be"); put('ぼ', "bo")
        put('ぱ', "pa"); put('ぴ', "pi"); put('ぷ', "pu"); put('ぺ', "pe"); put('ぽ', "po")
        // hiragana small kana
        put('ゃ', "ya"); put('ゅ', "yu"); put('ょ', "yo")
        put('ぁ', "a"); put('ぃ', "i"); put('ぅ', "u"); put('ぇ', "e"); put('ぉ', "o")
        put('っ', "tsu"); put('ゎ', "wa"); put('ゕ', "ka"); put('ゖ', "ke")
        // katakana basic gojuon
        put('ア', "a"); put('イ', "i"); put('ウ', "u"); put('エ', "e"); put('オ', "o")
        put('カ', "ka"); put('キ', "ki"); put('ク', "ku"); put('ケ', "ke"); put('コ', "ko")
        put('サ', "sa"); put('シ', "shi"); put('ス', "su"); put('セ', "se"); put('ソ', "so")
        put('タ', "ta"); put('チ', "chi"); put('ツ', "tsu"); put('テ', "te"); put('ト', "to")
        put('ナ', "na"); put('ニ', "ni"); put('ヌ', "nu"); put('ネ', "ne"); put('ノ', "no")
        put('ハ', "ha"); put('ヒ', "hi"); put('フ', "fu"); put('ヘ', "he"); put('ホ', "ho")
        put('マ', "ma"); put('ミ', "mi"); put('ム', "mu"); put('メ', "me"); put('モ', "mo")
        put('ヤ', "ya"); put('ユ', "yu"); put('ヨ', "yo")
        put('ラ', "ra"); put('リ', "ri"); put('ル', "ru"); put('レ', "re"); put('ロ', "ro")
        put('ワ', "wa"); put('ヲ', "o"); put('ン', "n")
        // katakana dakuten / handakuten
        put('ガ', "ga"); put('ギ', "gi"); put('グ', "gu"); put('ゲ', "ge"); put('ゴ', "go")
        put('ザ', "za"); put('ジ', "ji"); put('ズ', "zu"); put('ゼ', "ze"); put('ゾ', "zo")
        put('ダ', "da"); put('ヂ', "ji"); put('ヅ', "zu"); put('デ', "de"); put('ド', "do")
        put('バ', "ba"); put('ビ', "bi"); put('ブ', "bu"); put('ベ', "be"); put('ボ', "bo")
        put('パ', "pa"); put('ピ', "pi"); put('プ', "pu"); put('ペ', "pe"); put('ポ', "po")
        put('ヴ', "vu")
        // prolonged sound mark: transparent in sort keys (ガール -> gaaru)
        put('ー', "")
        // katakana small kana
        put('ャ', "ya"); put('ュ', "yu"); put('ョ', "yo")
        put('ァ', "a"); put('ィ', "i"); put('ゥ', "u"); put('ェ', "e"); put('ォ', "o")
        put('ッ', "tsu"); put('ヮ', "wa"); put('ヵ', "ka"); put('ヶ', "ke")
        put('ヰ', "i"); put('ヱ', "e")
    }

    /** Trailing pinyin tone digit, e.g. "zhong1" -> "zhong". */
    private val TONE_DIGIT = Regex("[0-9]+$")

    /**
     * Builds the sort key for a title/name. Empty input yields an empty key.
     */
    fun generate(text: String): String = buildString(text.length * 2) {
        for (c in text) {
            when {
                Pinyin.isChinese(c) -> {
                    // TinyPinyin returns the character itself when it has no
                    // dictionary entry; keep those as-is rather than dropping.
                    val pinyin = Pinyin.toPinyin(c)
                    if (pinyin.isNotEmpty() && pinyin != c.toString()) {
                        append(TONE_DIGIT.replace(pinyin, "").lowercase())
                    } else {
                        append(c.lowercaseChar())
                    }
                }
                KANA_TO_ROMAJI.containsKey(c) -> append(KANA_TO_ROMAJI.getValue(c))
                else -> append(c.lowercaseChar())
            }
        }
    }
}
