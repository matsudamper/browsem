# Paparazzi

## 共通ルール

### UI 変更時
- `@Preview` を追加/更新しスナップショットを撮影する
- 撮影結果はセッションのチャットに貼る
- 画像なしで UI 作業完了にしない

## リポジトリ固有ルール

### 画像の扱い
- スナップショット画像はコミットしない

### 実行
- `./gradlew test` では Paparazzi は自動除外

```bash
./gradlew :app:verifyPaparazziDebug
./gradlew :app:recordPaparazziDebug -Dpaparazzi.filter="PreviewName"
```
