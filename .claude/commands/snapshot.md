Paparazzi で `@Preview` コンポーザブルのスクリーンショットを撮影するスキルです。

UI コンポーネントの実装・変更（`@Preview` の追加・修正を含む）が完了したタイミングで積極的に使用してください。

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

## フィルター仕様

- クラスの完全修飾名（パッケージ名を含む）または Preview の `name` パラメータに対して大文字小文字を区別せず部分一致
- 例: `BrowserToolBar` → `BrowserToolBarKt` クラスの Preview のみ、`settings` → settings パッケージ配下のみ
