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

**Agent ツール（subagent_type=general-purpose）を使って以下のタスクをサブエージェントに委譲すること。**

サブエージェントへの指示内容:

1. `$ARGUMENTS` が空かどうかを確認する。
2. 以下のコマンドを Bash ツールで実行する（引数が空の場合は `-Dpaparazzi.filter` を省略）:
   - 引数あり: `./gradlew recordPaparazziDebug -Dpaparazzi.filter=$ARGUMENTS`
   - 引数なし: `./gradlew recordPaparazziDebug`
3. 実行結果を返す。
   - 成功時: `app/src/test/snapshots/images/` 以下に保存されたファイル一覧を含める。
   - 失敗時: エラーログの該当箇所を含める。

サブエージェントの結果を受け取ったら、ユーザーに成否と保存先を伝える。
