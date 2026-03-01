---
name: snapshot
description: UI コンポーネントの実装・変更（@Preview の追加・修正を含む）が完了したタイミングで Paparazzi スクリーンショットを撮影します。
context: fork
agent: general-purpose
argument-hint: "[フィルター文字列]"
---

## 手順

1. `$ARGUMENTS` が空かどうかを確認する。
2. 以下のコマンドを Bash ツールで実行する（引数が空の場合は `-Dpaparazzi.filter` を省略）:
   - 引数あり: `./gradlew recordPaparazziDebug -Dpaparazzi.filter=$ARGUMENTS`
   - 引数なし: `./gradlew recordPaparazziDebug`
3. 実行結果を返す。
   - 成功時: `app/src/test/snapshots/images/` 以下に保存されたファイル一覧を含める。
   - 失敗時: エラーログの該当箇所を含める。

## フィルター仕様

フィルターは以下の2つに対して**大文字小文字を区別せず部分一致**で適用される:

1. **クラスの完全修飾名** (`declaringClass.qualifiedName`)
   Kotlin のトップレベル関数は `<ファイル名>Kt` というクラス名になる。
   例: `BrowserToolBar.kt` → `net.matsudamper.browser.BrowserToolBarKt`

2. **`@Preview` の `name` パラメータ**
   `@Preview(name = "Dark")` のように指定した場合のみ有効。省略時は空文字のため対象外。

### 具体的な指定例

| 引数 | マッチするもの |
|---|---|
| （省略） | 全 Preview |
| `BrowserToolBar` | `net.matsudamper.browser.BrowserToolBarKt` の Preview |
| `net.matsudamper.browser` | パッケージ配下の全 Preview（現状は全件と同じ） |
| `settings` | `net.matsudamper.browser.settings` 配下の Preview |
| `Dark` | `@Preview(name = "Dark")` と指定された Preview |
