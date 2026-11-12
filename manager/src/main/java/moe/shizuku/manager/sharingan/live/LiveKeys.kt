package moe.shizuku.manager.sharingan.live

import android.content.Context
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.keys.SecureStore

/**
 * v2.1.0-alpha — LIVE BRAIN CREDENTIALS.
 *
 * The live session (watch + hear + act) needs a REAL vision-language model.
 * This build ships with a temporary NVIDIA integrate endpoint (provided by the
 * app owner FOR FIELD TESTING of the gift build): it works out of the box with
 * zero setup — a hands-disabled user must never fight a settings screen.
 *
 * OVERRIDE RULE (honest, no magic): if the user configured the generic
 * "OpenAI-compatible" provider in aMiNo's Keys page (key + base URL), the live
 * brain uses THAT instead — same wire protocol, user's own key and billing.
 * Any other provider choice keeps the built-in live endpoint.
 *
 * The key is a test credential, never logged, never printed, only sent in the
 * Authorization header of the live requests.
 */
object LiveKeys {

    private const val PREFS = "amino_live_settings"
    private const val K_OVERRIDE = "live_use_openai_compat"

    const val BUILTIN_BASE = "https://integrate.api.nvidia.com/v1"
    const val BUILTIN_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"
    // Temporary field-test credential supplied by the app owner (explicitly
    // authorized for this build). Replaceable at runtime via the Keys page
    // (choose "OpenAI-compatible" to route live through your own endpoint).
    private val BUILTIN_KEY = (
        "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"
    ).toCharArray()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** User opt-in switch: route live through the configured openai_compat provider. */
    fun overrideWithOpenAiCompat(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_OVERRIDE, value).apply()
    }

    fun overrideWithOpenAiCompat(context: Context): Boolean =
        prefs(context).getBoolean(K_OVERRIDE, false)

    private fun useBuiltin(context: Context): Boolean {
        if (!overrideWithOpenAiCompat(context)) return true
        return !(ApiKeysStore.provider(context) == ApiKeysStore.PROVIDER_OPENAI_COMPAT &&
            ApiKeysStore.hasKey(context))
    }

    data class Creds(val baseUrl: String, val model: String, val key: CharArray, val builtin: Boolean)

    fun creds(context: Context): Creds {
        if (useBuiltin(context)) {
            return Creds(BUILTIN_BASE, BUILTIN_MODEL, BUILTIN_KEY.copyOf(), builtin = true)
        }
        val key = SecureStore.get(context, "apikey_" + ApiKeysStore.PROVIDER_OPENAI_COMPAT)
            ?: return Creds(BUILTIN_BASE, BUILTIN_MODEL, BUILTIN_KEY.copyOf(), builtin = true)
        val base = ApiKeysStore.baseUrl(context)?.takeIf { it.isNotBlank() }
            ?: "https://api.openai.com/v1"
        val model = ApiKeysStore.model(context).takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
        return Creds(base.trimEnd('/'), model, key, builtin = false)
    }
}
