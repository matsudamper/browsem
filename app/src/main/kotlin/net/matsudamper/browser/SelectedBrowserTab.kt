package net.matsudamper.browser

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 画面が表示している tabId に対応する [BrowserTab] を解決する。
 * まだ存在しない場合は復元完了を待ってから作成する。未解決の間は null を返す。
 */
@Composable
internal fun rememberSelectedBrowserTab(
    tabId: String,
    homepageUrl: String,
    browserTabController: BrowserTabController,
): BrowserTab? {
    // タブの登録簿は Compose の状態ではないため、findTab の結果だけでは追加・削除で再コンポーズされない。
    // タブの増減で更新される tabStoreState を読み、解決をやり直す。
    val tabStoreState by browserTabController.tabStoreState.collectAsState()
    val selectedTab = remember(tabId, tabStoreState) { browserTabController.findTab(tabId) }
    LaunchedEffect(tabId, homepageUrl, selectedTab) {
        // closeTab で閉じたタブは再作成しない。
        // NavDisplay の遷移アニメーション中に画面が残っている間に
        // selectedTab=null で再コンポーズされてもホームページタブを作らないようにする。
        if (selectedTab == null && !browserTabController.wasTabClosed(tabId)) {
            // プロセス死後の savedInstanceState 復元時は画面が compose される時点で
            // タブ復元がまだ完了していない。復元完了前に getOrCreateTab を呼ぶと、空の registry に
            // ホームページタブが sortOrder=0 で永続化されてしまう。
            // 復元完了を待ってから存在確認し、それでも存在しない場合のみ作成する。
            browserTabController.restoreComplete.await()
            if (browserTabController.findTab(tabId) == null && !browserTabController.wasTabClosed(tabId)) {
                browserTabController.getOrCreateTab(
                    tabId = tabId,
                    homepageUrl = homepageUrl,
                )
            }
        }
    }
    if (selectedTab == null) {
        // フォアグラウンド遷移直後に findTab が空を返すケースのフレーキー解析用ログ。
        // 該当時間帯に UrlBar が semantics tree から消えるテスト失敗との突き合わせに使う。
        LaunchedEffect(tabId) {
            Log.d(
                "SelectedBrowserTab",
                "selectedTab=null tabId=$tabId wasClosed=${browserTabController.wasTabClosed(tabId)}",
            )
        }
    }
    return selectedTab
}
