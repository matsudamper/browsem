package net.matsudamper.browser

/**
 * 保存済みの表示順に従って拡張機能アクションを並べ替える。
 * 保存順に含まれない (新しくインストールされた) 拡張機能は元の順序のまま末尾へ置く。
 */
internal fun <T> sortByExtensionActionOrder(
    items: List<T>,
    order: List<String>,
    idOf: (T) -> String,
): List<T> {
    if (order.isEmpty()) return items
    val orderIndexes = order.withIndex().associate { (index, id) -> id to index }
    return items.withIndex().sortedWith(
        compareBy(
            { (_, item) -> orderIndexes[idOf(item)] ?: Int.MAX_VALUE },
            { (index, _) -> index },
        ),
    ).map { it.value }
}

/**
 * タブで見えている拡張機能の並び替え結果を、保存済みの全体順へ反映する。
 * 保存順は全タブ共通のため、そのタブに出ていない拡張機能の位置は変えずに、
 * 見えている拡張機能が占めていた位置だけを新しい順序で埋め直す。
 */
internal fun mergeVisibleExtensionActionOrder(
    savedOrder: List<String>,
    visibleOrder: List<String>,
): List<String> {
    val base = savedOrder + visibleOrder.filter { it !in savedOrder }
    val visibleIds = visibleOrder.toSet()
    val merged = base.toMutableList()
    var visibleIndex = 0
    base.forEachIndexed { index, id ->
        if (id in visibleIds) {
            merged[index] = visibleOrder[visibleIndex]
            visibleIndex += 1
        }
    }
    return merged
}

/**
 * [fromIndex] の要素を [toIndex] へ 1 件移動した ID 並びを返す。
 * インデックスが範囲外、または移動が不要な場合は null を返す。
 */
internal fun moveExtensionActionOrder(
    ids: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String>? {
    if (fromIndex == toIndex) return null
    if (fromIndex !in ids.indices || toIndex !in ids.indices) return null
    return ids.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
