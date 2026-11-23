package net.matsudamper.browser.feature.webauthncompat

import android.annotation.TargetApi
import android.content.Context
import android.credentials.CredentialManager
import android.credentials.CredentialOption
import android.credentials.GetCredentialException
import android.credentials.GetCredentialRequest
import android.credentials.GetCredentialResponse
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * navigator.credentials.get を GeckoView ではなく Credential Manager で直接処理する。
 * GeckoView は Credential Manager から候補が返らないと Play の特権 FIDO2 API へ回し、
 * 特権を持たないアプリではその失敗が処理されず Promise が止まるため。
 */
class WebAuthnCompatCredentialBridge(
    private val context: Context,
    private val onLog: (String) -> Unit,
) : WebExtension.MessageDelegate {
    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender,
    ): GeckoResult<Any>? {
        val request = message as? JSONObject ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return GeckoResult.fromValue(JSONObject().put(KEY_FALLBACK, true))
        }
        return getCredential(
            requestJson = request.getString(KEY_REQUEST_JSON),
            origin = request.getString(KEY_ORIGIN),
        )
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getCredential(requestJson: String, origin: String): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        val challenge = JSONObject(requestJson).getString("challenge")
        // GeckoView と同じく clientDataJSON はブラウザ側で作り、provider にはハッシュだけを渡す。
        // provider が返す clientDataJSON はハッシュを渡した場合に使えないため、自前のものに差し替える。
        val clientDataJson = buildClientDataJson(challenge = challenge, origin = origin)
        val requestBundle = Bundle().apply {
            putString(BUNDLE_KEY_SUBTYPE, BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION)
            putString(BUNDLE_KEY_REQUEST_JSON, requestJson)
            putByteArray(
                BUNDLE_KEY_CLIENT_DATA_HASH,
                MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray()),
            )
            putBoolean(BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED, hasAllowCredentials(requestJson))
        }
        val credentialRequest = GetCredentialRequest.Builder(Bundle())
            .addCredentialOption(
                CredentialOption.Builder(TYPE_PUBLIC_KEY_CREDENTIAL, requestBundle, requestBundle).build(),
            )
            .setAlwaysSendAppInfoToProvider(true)
            .setOrigin(origin)
            .build()
        val manager = context.getSystemService(CredentialManager::class.java)
        if (manager == null) {
            return GeckoResult.fromValue(errorResponse(type = "NO_CREDENTIAL_MANAGER", message = null))
        }
        log("CredentialManager.getCredential start: origin=$origin")
        try {
            manager.getCredential(
                context,
                credentialRequest,
                CancellationSignal(),
                context.mainExecutor,
                object : OutcomeReceiver<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(response: GetCredentialResponse) {
                        result.complete(successResponse(response, clientDataJson))
                    }

                    override fun onError(error: GetCredentialException) {
                        log("CredentialManager.getCredential error: type=${error.type} message=${error.message}")
                        result.complete(errorResponse(type = error.type, message = error.message))
                    }
                },
            )
        } catch (error: SecurityException) {
            log("CredentialManager.getCredential threw: $error")
            return GeckoResult.fromValue(errorResponse(type = error.javaClass.name, message = error.message))
        }
        return result
    }

    private fun successResponse(response: GetCredentialResponse, clientDataJson: String): JSONObject {
        val responseJson = response.credential.data.getString(BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON)
            ?: return errorResponse(type = "NO_AUTHENTICATION_RESPONSE_JSON", message = null)
        log("CredentialManager.getCredential result: $responseJson")
        return try {
            val credential = JSONObject(responseJson)
            credential.getJSONObject("response").put("clientDataJSON", clientDataJson.toBase64Url())
            JSONObject().put(KEY_CREDENTIAL, credential)
        } catch (error: JSONException) {
            errorResponse(type = "INVALID_AUTHENTICATION_RESPONSE_JSON", message = error.message)
        }
    }

    private fun log(message: String) {
        Log.d(WebAuthnCompatLogMessageDelegate.TAG, message)
        onLog(message)
    }

    private fun errorResponse(type: String, message: String?): JSONObject {
        return JSONObject()
            .put(KEY_ERROR_TYPE, type)
            .put(KEY_ERROR_MESSAGE, message.orEmpty())
    }

    private fun hasAllowCredentials(requestJson: String): Boolean {
        return (JSONObject(requestJson).optJSONArray("allowCredentials")?.length() ?: 0) > 0
    }

    private fun buildClientDataJson(challenge: String, origin: String): String {
        return "{\"type\":\"webauthn.get\"," +
            "\"challenge\":${JSONObject.quote(challenge)}," +
            "\"origin\":${JSONObject.quote(origin)}," +
            "\"crossOrigin\":false}"
    }

    private fun String.toBase64Url(): String {
        return Base64.encodeToString(toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val NATIVE_APP = "browsem_webauthn_compat_credential"

        private const val KEY_REQUEST_JSON = "requestJson"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_FALLBACK = "fallback"
        private const val KEY_CREDENTIAL = "credential"
        private const val KEY_ERROR_TYPE = "errorType"
        private const val KEY_ERROR_MESSAGE = "errorMessage"

        private const val CM_PREFIX = "androidx.credentials."
        private const val TYPE_PUBLIC_KEY_CREDENTIAL = "${CM_PREFIX}TYPE_PUBLIC_KEY_CREDENTIAL"
        private const val BUNDLE_KEY_SUBTYPE = "${CM_PREFIX}BUNDLE_KEY_SUBTYPE"
        private const val BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION =
            "${CM_PREFIX}BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION"
        private const val BUNDLE_KEY_REQUEST_JSON = "${CM_PREFIX}BUNDLE_KEY_REQUEST_JSON"
        private const val BUNDLE_KEY_CLIENT_DATA_HASH = "${CM_PREFIX}BUNDLE_KEY_CLIENT_DATA_HASH"
        private const val BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED = "${CM_PREFIX}BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED"
        private const val BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON =
            "${CM_PREFIX}BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON"
    }
}
