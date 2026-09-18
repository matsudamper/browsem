package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.matsudamper.browser.feature.findinpage.FindInPageWebExtension
import org.mozilla.geckoview.GeckoSession

private enum class FindInPageMode {
    Closed,
    Normal,
    Regex,
}

/**
 * タブ 1 つあたりのページ内検索状態。
 *
 * 通常検索は GeckoView の finder、正規表現検索は [FindInPageWebExtension] が担当するため、
 * モード切り替え時には使っていない側の結果をクリアする。
 */
@Stable
internal class FindInPageState(
    private val findInPageWebExtension: FindInPageWebExtension,
    private val session: () -> GeckoSession,
) {
    private var mode by mutableStateOf(FindInPageMode.Closed)

    val isVisible: Boolean get() = mode != FindInPageMode.Closed

    /** 正規表現モードが有効かどうか */
    val isRegex: Boolean get() = mode == FindInPageMode.Regex

    var query by mutableStateOf("")
        private set

    var matchCurrent by mutableIntStateOf(0)
        private set

    var matchTotal by mutableIntStateOf(0)
        private set

    /** 無効な正規表現が入力された場合のエラーメッセージ */
    var queryError by mutableStateOf<String?>(null)
        private set

    fun open() {
        mode = FindInPageMode.Normal
    }

    fun close() {
        val previousMode = mode
        mode = FindInPageMode.Closed
        if (previousMode == FindInPageMode.Regex) {
            findInPageWebExtension.clear(session())
        } else {
            session().finder.clear()
        }
        query = ""
        matchCurrent = 0
        matchTotal = 0
        queryError = null
    }

    fun onQueryChange(newQuery: String) {
        query = newQuery
        queryError = null
        if (newQuery.isEmpty()) {
            clearMatches()
        } else if (isRegex) {
            findInPageWebExtension.search(session(), newQuery, isRegex = true)
        } else {
            findWithFinder(newQuery, 0)
        }
    }

    fun findNext() {
        if (query.isEmpty()) return
        if (isRegex) {
            findInPageWebExtension.findNext(session())
        } else {
            findWithFinder(query, 0)
        }
    }

    fun findPrevious() {
        if (query.isEmpty()) return
        if (isRegex) {
            findInPageWebExtension.findPrevious(session())
        } else {
            findWithFinder(query, GeckoSession.FINDER_FIND_BACKWARDS)
        }
    }

    fun toggleRegex() {
        val newMode = if (mode == FindInPageMode.Regex) FindInPageMode.Normal else FindInPageMode.Regex
        mode = newMode
        queryError = null
        if (query.isEmpty()) return
        if (newMode == FindInPageMode.Regex) {
            session().finder.clear()
            findInPageWebExtension.search(session(), query, isRegex = true)
        } else {
            findInPageWebExtension.clear(session())
            findWithFinder(query, 0)
        }
    }

    /** 正規表現検索の結果を拡張機能から受け取る */
    fun onRegexSearchResult(current: Int, total: Int, error: String?) {
        matchCurrent = current
        matchTotal = total
        queryError = if (error == "invalid_regex") "無効な正規表現です" else null
    }

    private fun clearMatches() {
        if (isRegex) {
            findInPageWebExtension.clear(session())
        } else {
            session().finder.clear()
        }
        matchCurrent = 0
        matchTotal = 0
    }

    private fun findWithFinder(searchQuery: String, flags: Int) {
        session().finder.find(searchQuery, flags).then<Void?> { result ->
            matchCurrent = result?.current ?: 0
            matchTotal = result?.total ?: 0
            null
        }
    }
}
