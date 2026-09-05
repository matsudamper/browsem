# Compose / UiState

## UiState
- UI と ViewModel の接点は UiState（表示もイベントも）
- ViewModel の public 関数を UI から直接呼ばない。Callbacks / Event interface を UiState 経由で使う
- UiState に表示に使わない値（id など）を載せない
- UiState にデフォルト値を付けない（該当リポの方針がある場合はそれに従う）

## Compose
- 画面 Composable は必要に応じて `internal`
- State Holder には `@Stable`
- `material-icons-extended` / Compose Material Icons Extended は使わない。アイコンは XML/SVG 等
- Composable 内の早期 return は避け、if-else / when で分岐する（該当リポの方針がある場合）
