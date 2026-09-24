# Add project specific ProGuard rules here.

# protobuf-javalite はフィールド名をリフレクションで参照するため、難読化・削除させない
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
