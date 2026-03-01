特定の `@Preview` コンポーザブルのスクリーンショットを Paparazzi で撮影します。

## 引数

`$ARGUMENTS` にフィルター文字列を指定します（省略時は全件撮影）。

- フィルターは **クラス名** または **Preview の `name` パラメータ** に対して大文字小文字を区別せず部分一致で適用されます。
- 例: `BrowserToolBar` → `BrowserToolBarKt` クラスの Preview を撮影

## 手順

1. 引数 `$ARGUMENTS` が空でなければ `-Dpaparazzi.filter=$ARGUMENTS` を付けて実行する。空であれば付けない。
2. 以下のコマンドを実行する:

```
./gradlew recordPaparazziDebug -Dpaparazzi.filter=$ARGUMENTS
```

3. 実行結果（成功/失敗）とスクリーンショットの保存先パスをユーザーに伝える。
   - 成功時: `app/src/test/snapshots/images/` 以下に保存されることを伝える。
   - 失敗時: エラーログを確認して原因を報告する。
