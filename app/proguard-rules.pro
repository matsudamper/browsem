# Add project specific ProGuard rules here.

# ML Kit GenAI の構造化出力は @Generable クラスのフィールドをリフレクションで読み書きし、
# スキーマは KSP 生成の GenerableProvider を ServiceLoader で探すため、名前を保つ
-keep @com.google.mlkit.genai.schema.annotations.Generable class * { *; }
-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider
