---
name: icon
description: 必要なアイコンの説明からGoogle Fontsで適切なアイコンを探し、Android Vector DrawableのXMLをres/drawableにダウンロードする
argument-hint: <アイコンの説明>
allowed-tools: Bash(curl *) Write WebFetch
---

$ARGUMENTS に適した検索ワードを考え、以下のURLで検索してアイコン名を特定する：

```
https://fonts.google.com/icons?icon.query={検索ワード}
```

特定したアイコン名（`{icon_name}`）を使い、以下のURLからXMLをダウンロードして `res/drawable/ic_{icon_name}.xml` に配置する：

```
https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/{icon_name}/materialsymbolsoutlined/{icon_name}_24px.xml
```
