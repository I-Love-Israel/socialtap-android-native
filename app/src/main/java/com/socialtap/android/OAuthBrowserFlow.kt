package com.socialtap.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/** Keeps cookies in memory only. Used for the short Google handoff session, never persisted. */
class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            store.removeAll { old -> cookies.any { it.name == old.name } }
            store.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(store) { store.filter { it.matches(url) } }
}

/**
 * Turns a Google sign-in into OAuth bearer tokens.
 *
 * 1. Google sign-in in a browser ends at socialtap://auth/google with a handoff code.
 * 2. POST /api/auth/google/exchange with that code and the PKCE verifier. This sets a web session cookie.
 * 3. With that session, GET then POST /api/oauth/authorize (consent, approved as the form describes).
 * 4. The redirect to socialtap://oauth/callback carries an OAuth code, which is exchanged for tokens.
 */
class OAuthBrowserFlow(private val api: SocialTapApi) {

    private val http = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun exchangeGoogleHandoff(code: String, verifier: String) {
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(Config.BASE_URL + "/api/auth/google/exchange")
            .post(body)
            .header("User-Agent", Config.USER_AGENT)
            .build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Google sign-in could not be completed (HTTP ${response.code})")
                }
                if (!JSONObject(text).optBoolean("success", false)) {
                    throw IOException("Google sign-in was not accepted")
                }
            }
        }
    }

    /** Approves the SocialTap consent form with the current web session and stores the OAuth tokens. */
    suspend fun authorizeWithSession() {
        val verifier = Pkce.randomString(48)
        val state = Pkce.randomString(32)
        val challenge = Pkce.challengeFor(verifier)
        val scopes = (Config.REQUIRED_SCOPES + Config.OPTIONAL_SCOPES).joinToString(" ")
        val query = listOf(
            "response_type" to "code",
            "client_id" to Config.OAUTH_CLIENT_ID,
            "redirect_uri" to Config.REDIRECT_URI,
            "scope" to scopes,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        ).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }

        val page = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${Config.BASE_URL}/api/oauth/authorize?$query")
                .header("User-Agent", Config.USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    throw IOException("Finish sign-in in the browser first (SocialTap asked for more: ${response.header("Location")?.substringBefore('?') ?: "a page"})")
                }
                if (!response.isSuccessful) throw IOException("Could not load the approval page (HTTP ${response.code})")
                response.body?.string().orEmpty()
            }
        }
        val csrf = OAuthForm.csrfToken(page) ?: throw IOException("The approval page changed; please try again")

        val form = FormBody.Builder()
            .add("response_type", "code")
            .add("client_id", Config.OAUTH_CLIENT_ID)
            .add("redirect_uri", Config.REDIRECT_URI)
            .add("scope", scopes)
            .add("state", state)
            .add("code_challenge", challenge)
            .add("code_challenge_method", "S256")
            .add("csrf_token", csrf)
            .add("decision", "approve")
            .add("session_duration", "forever")
        Config.OPTIONAL_SCOPES.forEach { form.add("approved_optional_scopes", it) }

        val location = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${Config.BASE_URL}/api/oauth/authorize")
                .post(form.build())
                .header("User-Agent", Config.USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isRedirect) throw IOException("Approval was not accepted (HTTP ${response.code})")
                response.header("Location").orEmpty()
            }
        }

        val denied = OAuthForm.queryParam(location, "error")
        if (denied != null) throw IOException("Access was not approved ($denied)")
        val code = OAuthForm.queryParam(location, "code")
        val returnedState = OAuthForm.queryParam(location, "state")
        if (code == null || returnedState != state) throw IOException("The approval response did not match")
        api.exchangeCode(code, verifier)
    }
}

/** Parsing helpers for SocialTap's HTML form and redirect URLs. Kept pure so they can be unit-tested. */
object OAuthForm {

    private val inputTag = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val valueAttr = Regex("""\bvalue\s*=\s*"([^"]*)"|\bvalue\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
    private val nameAttr = Regex("""\bname\s*=\s*["']csrf_token["']""", RegexOption.IGNORE_CASE)

    /** The hidden csrf_token value rendered in the consent form, or null if not present. */
    fun csrfToken(html: String): String? {
        for (match in inputTag.findAll(html)) {
            val tag = match.value
            if (!nameAttr.containsMatchIn(tag)) continue
            val value = valueAttr.find(tag) ?: continue
            val raw = value.groupValues[1].ifEmpty { value.groupValues[2] }
            return raw.replace("&amp;", "&")
        }
        return null
    }

    /** A query parameter from a URL such as socialtap://oauth/callback?code=...&state=... */
    fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key == name) {
                return URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
        }
        return null
    }
}
