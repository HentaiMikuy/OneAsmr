package com.oneasmr.app.ui.singles

/** 排序字段与方向分开，切换字段时沿用原有的默认方向。 */
enum class SinglesOrder(val label: String, val defaultDescending: Boolean) {
    ADDED("加入时间", true),
    TITLE("标题", false),
    DURATION("时长", true),
    ;

    fun directionLabel(descending: Boolean): String {
        val detail = when (this) {
            ADDED -> if (descending) "最近加入优先" else "最早加入优先"
            TITLE -> if (descending) "标题倒序" else "标题正序"
            DURATION -> if (descending) "从长到短" else "从短到长"
        }
        return "${if (descending) "降序" else "升序"}（$detail）"
    }
}

/** 与原有排序一样仅在会话内保存；字段与方向作为同一份状态更新。 */
internal data class SinglesSort(
    val order: SinglesOrder = SinglesOrder.ADDED,
    val descending: Boolean = order.defaultDescending,
) {
    /** 重复选择当前字段时反转方向，新字段使用其默认方向。 */
    fun select(newOrder: SinglesOrder): SinglesSort =
        if (newOrder == order) copy(descending = !descending) else SinglesSort(newOrder)

    fun sort(rows: List<SingleFileRow>): List<SingleFileRow> {
        var comparator = when (order) {
            SinglesOrder.ADDED -> compareBy<SingleFileRow> { it.file.addedAt }
            SinglesOrder.TITLE -> compareBy<SingleFileRow> { it.file.titleSortKey }
            SinglesOrder.DURATION -> compareBy<SingleFileRow> { it.file.durationMs }
        }
        if (descending) comparator = comparator.reversed()
        if (order == SinglesOrder.DURATION) {
            // 未知时长在两个方向下都沉底，不能直接反转整个列表。
            comparator = compareBy<SingleFileRow> { it.file.durationMs == null }.then(comparator)
        }
        return rows.sortedWith(comparator.thenBy { it.file.id })
    }
}
