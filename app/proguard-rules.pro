# Add project specific ProGuard rules here.

# Cast SDK OptionsProvider はAndroidManifest.xmlのmeta-dataから文字列で参照されるため、
# R8によるリネームや削除を防ぐ。
-keep class net.matsudamper.browser.cast.CastOptionsProvider { *; }
