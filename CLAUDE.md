# CLAUDE.md

## ビルド確認コマンド

### デバッグビルド

```bash
./gradlew :app:assembleDebug
```

### リリースビルド

```bash
./gradlew :app:assembleRelease
```

### 全モジュールのビルド確認（APK生成なし）

```bash
./gradlew assembleDebug
```

### ユニットテスト

```bash
./gradlew test
```

### Paparazzi スクリーンショットテスト

```bash
./gradlew :app:testDebugUnitTest -Dpaparazzi.filter=
```
