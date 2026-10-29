# media_bridge のセッションと初回応答

## 生成経路の確認

| 経路 | delegate の登録 |
| --- | --- |
| BrowserTabFactory の通常・復元タブ | createRegisteredTab → onTabSessionCreated で登録 |
| onNewSession / window.open の子をタブに載せる場合 | createRegisteredTab で登録。Gecko に返す前のタブ生成時にフックを実行 |
| CustomTab / WebApp | 各 ViewModel の onTabSessionCreated で登録 |
| WebExtensionActionController の popupSession | 未登録。拡張自身の moz-extension ページには他の拡張の content script は入らない。通常ページへ遷移する場合や HTTP フレームが入る場合は未応答フォールバックの対象 |
| ExtensionSettingsScreenViewModel | 未登録。拡張設定ページ自体は対象外。通常ページへの遷移・HTTP フレームは未応答フォールバックの対象 |
| WindowOpenCustomTabLauncher から別 Activity へ渡す子・連鎖する子 | createSessionForProfile で生成し、引き渡し先でタブへ載せるまでは未登録 |
| CustomTabsWarmupStore の事前読み込み | タブとして引き取るまでは未登録 |
| CustomTabHandoffStore で本体に返すセッション | 同じセッションを保持し、本体の createRegisteredTab で再登録。registerSession は冪等 |

未登録経路をすべてメディア通知の管理下へ入れると、短命な拡張ページや事前読み込みにも登録・解放を追加する必要がある。このため、初回応答がない可視ドキュメントだけを期限付きで解放する方式を選んだ。

## インストールと初回応答

- インストール完了前に registerSession されたセッションは registeredSessions に残り、完了コールバックで delegate を張る。
- インストール済み拡張の script が delegate より先に動いた場合は、接続を再試行する。
- インストール前に読み込みが始まったドキュメントには、manifest の宣言だけで既存ページへの後付け注入は保証されない。script 自体が動いていないページは、今回の ready 待ちには入らない。
- 初回 playbackPolicy が届けば、その場で ready を解放する。snapshot 用の 120ms デバウンスは初回応答には使わない。
- ready 待ちの play() は、待機中にページが同じ要素の pause() を呼んだ場合だけ AbortError で取り消す。保留した autoplay も同様に取り消す。
- ネイティブ状態を一度も受け取れない場合は、script 起動から 250ms 以降に visibilityState が visible なら解放する。hidden の間は解放せず、visible への変化時に再判定する。
- 一度でもネイティブ状態を受信したページは、切断後も visibility だけでは解放しない。特に、Gecko の表示状態が visible のままでもネイティブの背景指定を優先する。
- フォールバック後にネイティブの背景指定が届いた場合も、直ちに遮断へ戻す。

待機時間の境界（249ms / 250ms）と早い応答（5ms）はテスト時計で検証する。実機の IPC 所要時間は未計測。タイマーの実行が OS やページの負荷で遅れた場合、250ms は厳密な実時間の上限ではない。

参照:

- [Mozilla: content scripts の読み込みと制限](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Content_scripts)
- [Mozilla: content_scripts と run_at](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/content_scripts)
