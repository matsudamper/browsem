# CLAUDE.md

## ビルド確認コマンド

### デバッグビルド

```bash
./gradlew :app:assembleDebug
```

### ユニットテスト

```bash
./gradlew test
```

## コーディング

コメントは日本語で

## プロジェクト概要

GeckoView ベースの Android ブラウザアプリ。Jetpack Compose + Material 3 + Navigation 3。

## モジュール構成

- `:app` — メインアプリ (UI + GeckoView)
- `:data` — DataStore 永続化 (設定・タブ状態の Repository)
- `:proto` — Protocol Buffers スキーマ定義

依存: `:app` → `:data` → `:proto`

## コード構成ガイド (app モジュール)

パッケージ: `app/src/main/java/net/matsudamper/browser/`

### エントリポイント・全体制御
- `MainActivity.kt` — Activity。GeckoRuntime シングルトン管理
- `BrowserViewModel.kt` — メイン ViewModel。設定・タブ永続化
- `BrowserSessionController.kt` — タブの作成・復元・クローズを管理

### ナビゲーション (`navigation/`)
- `AppDestination.kt` — 画面遷移先定義 (Browser, Settings, Extensions, Tabs 等)
- `NavController.kt` — バックスタック操作

### ブラウザ画面
- `AppNavigation.kt` — ルーティング (BrowserApp Composable)
- `screen/browser/BrowserScreen.kt` — タブスワイプ切替アニメーション
- `GeckoBrowserTab.kt` — 単一タブの UI (GeckoView ホスト + ダイアログ)
- `BrowserTabScreenState.kt` — タブの State Holder (GeckoSession Delegate 実装。最大ファイル)
- `BrowserToolBar.kt` — URL バー + メニュー

### 各画面 (`screen/`)
- `screen/tab/` — タブ一覧画面
- `screen/settings/` — 設定画面
- `screen/extensions/` — 拡張機能管理画面
- `screen/notificationpermissions/` — 通知許可管理画面

### 機能別
- `translate/` — 翻訳 (GeckoView 内蔵 / ML Kit ローカル AI の2プロバイダ)
- `WebExtensionInstaller.kt` — 拡張機能インストールフロー
- `GeckoDownloadManager.kt` — 画像ダウンロード

### データ永続化 (data モジュール)
- `SettingsRepository.kt` — 設定の DataStore。`resolvedHomepageUrl()` / `resolvedSearchTemplate()`
- `TabRepository.kt` — タブ状態の DataStore
- Proto 定義: `proto/src/main/proto/browser_settings.proto`, `browser_tab_state.proto`

## 主要な設計ポイント

- **GeckoRuntime はシングルトン**: MainActivity で1回作成 → BrowserViewModel → BrowserSessionController に渡す
- **タブ管理**: BrowserSessionController がタブのライフサイクルを管理。`AppDestination.Browser(tabId, beforeTab)` でタブ切替
- **タブスワイプ**: BrowserScreen で BrowserToolBar の水平ドラッグを受け取り、前後タブのサムネイルと共にアニメーション
- **永続化**: Proto → DataStore → Repository → ViewModel (StateFlow) → Compose
