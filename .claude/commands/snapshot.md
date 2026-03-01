特定の `@Preview` コンポーザブルのスクリーンショットを Paparazzi で撮影します。

## 使い方

```
/snapshot [フィルター文字列]
```

- 引数を省略すると **全 Preview を撮影** します。
- フィルター文字列を指定すると、**クラスの完全修飾名**（パッケージ名を含む）または **Preview の `name` パラメータ** に対して大文字小文字を区別せず部分一致で絞り込みます。

### 例

| コマンド | 動作 |
|---|---|
| `/snapshot` | 全 Preview を撮影 |
| `/snapshot BrowserToolBar` | `BrowserToolBarKt` クラスの Preview のみ撮影 |
| `/snapshot settings` | `settings` パッケージ配下の Preview のみ撮影 |
| `/snapshot net.matsudamper.browser.settings` | 完全修飾パッケージで絞り込み |

## 手順

1. `$ARGUMENTS` が空かどうかを確認する。
2. 以下のコマンドを実行する（引数が空の場合は `-Dpaparazzi.filter` を省略）:
   - 引数あり: `./gradlew recordPaparazziDebug -Dpaparazzi.filter=$ARGUMENTS`
   - 引数なし: `./gradlew recordPaparazziDebug`
3. 実行結果をユーザーに伝える。
   - 成功時: `app/src/test/snapshots/images/` 以下に保存されたことを伝える。
   - 失敗時: エラーログを確認して原因を報告する。
