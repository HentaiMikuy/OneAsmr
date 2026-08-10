package com.oneasmr.app.domain.singlefile

/**
 * YouTube 链接/ID 解析(「关联视频链接」手动绑定入口)。纯 JVM。
 *
 * 为什么需要它:裸文件名(浏览器扩展/在线站下载、用户重命名)通常
 * 没有视频 ID,而按标题搜索要么依赖 YouTube Data API key、要么抓搜索页
 * HTML(反爬脆弱且误配率高)—— 都不做。让用户粘贴原视频链接,客户端
 * 解析出 ID 后走 oEmbed 补全,准确率 100%。
 */
object YouTubeLinkParser {

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** 各 URL 形态里紧跟 ID 的前缀段。 */
    private val URL_PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""), // watch?v=ID / ...&v=ID
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""/live/([A-Za-z0-9_-]{11})"""),
    )

    /**
     * 从用户输入提取 11 位视频 ID:裸 ID 原样接受;URL 按常见形态
     * (watch?v= / youtu.be/ / shorts/ / embed/ / live/)匹配。无法确定
     * 返回 null(调用方提示重试,绝不猜)。
     */
    fun extractId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (ID.matches(trimmed)) return trimmed
        for (pattern in URL_PATTERNS) {
            pattern.find(trimmed)?.let { return it.groupValues[1] }
        }
        return null
    }
}
