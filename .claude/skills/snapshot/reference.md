# フィルター仕様

フィルターは以下の2つに対して**大文字小文字を区別せず部分一致**で適用される:

1. **クラスの完全修飾名** (`declaringClass.qualifiedName`)
   Kotlin のトップレベル関数は `<ファイル名>Kt` というクラス名になる。
   例: `BrowserToolBar.kt` → `net.matsudamper.browser.BrowserToolBarKt`

2. **`@Preview` の `name` パラメータ**
   `@Preview(name = "Dark")` のように指定した場合のみ有効。省略時は空文字のため対象外。

## 具体的な指定例

| 引数 | マッチするもの |
|---|---|
| （省略） | 全 Preview |
| `BrowserToolBar` | `net.matsudamper.browser.BrowserToolBarKt` の Preview |
| `net.matsudamper.browser` | パッケージ配下の全 Preview（現状は全件と同じ） |
| `settings` | `net.matsudamper.browser.settings` 配下の Preview |
| `Dark` | `@Preview(name = "Dark")` と指定された Preview |
