from pathlib import Path


path = Path("app/src/main/kotlin/net/matsudamper/browser/translate/PageTranslationWebExtension.kt")
text = path.read_text()

replacements = [
    (
        "import org.mozilla.geckoview.GeckoResult\n",
        "",
    ),
    (
        """                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    if (json.optString("action") != "ready") return null
                    return GeckoResult.fromValue<Any>(
                        JSONObject().apply {
                            put("connect", true)
                        },
                    )
                }

""",
        "",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)

path.write_text(text)
