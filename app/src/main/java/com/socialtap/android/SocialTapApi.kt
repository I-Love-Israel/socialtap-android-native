package com.socialtap.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** REST calls against the SocialTap API, authenticated with the OAuth bearer token. */
class SocialTapApi(private val tokens: TokenStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshLock = Mutex()

    /** Called when SocialTap rejects the refresh token, so the app returns to sign-in. */
    var onSessionEnded: (() -> Unit)? = null

    suspend fun exchangeCode(code: String, verifier: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", Config.OAUTH_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", Config.REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        val json = postForm("/api/oauth/token", form)
        tokens.saveTokens(json.getString("access_token"), json.optString("refresh_token"))
    }

    /** Rotating refresh: each refresh replaces both tokens. Returns true when a new access token is stored. */
    suspend fun refreshTokens(): Boolean = refreshLock.withLock {
        val refresh = tokens.refreshToken ?: return@withLock false
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", Config.OAUTH_CLIENT_ID)
            .add("refresh_token", refresh)
            .build()
        try {
            val json = postForm("/api/oauth/token", form)
            tokens.saveTokens(json.getString("access_token"), json.optString("refresh_token"))
            true
        } catch (e: ApiException) {
            if (e.status == 400 || e.status == 401) {
                tokens.clear()
                onSessionEnded?.invoke()
            }
            false
        } catch (e: IOException) {
            false
        }
    }

    suspend fun account(): JSONObject = JSONObject(authed("GET", "/api/account"))

    suspend fun chats(): JSONArray = JSONArray(authed("GET", "/api/chats"))

    suspend fun messages(chatId: Long, afterId: Long = 0L): JSONArray {
        val path = if (afterId > 0) {
            "/messages?chat_id=$chatId&last_id=$afterId"
        } else {
            "/messages?chat_id=$chatId"
        }
        return JSONArray(authed("GET", path))
    }

    suspend fun sendMessage(chatId: Long, text: String): JSONObject {
        val json = JSONObject(
            authed("POST", "/send", form = mapOf("message" to text, "chat_id" to chatId.toString()))
        )
        return json.optJSONObject("message") ?: JSONObject()
    }

    suspend fun notifications(since: Long): JSONObject =
        JSONObject(authed("GET", "/api/notifications?since=$since"))

    suspend fun markRead(chatId: Long, messageId: Long) {
        authed(
            "POST",
            "/api/chat/read",
            json = JSONObject().put("chat_id", chatId).put("last_read_message_id", messageId)
        )
    }

    private suspend fun authed(
        method: String,
        path: String,
        form: Map<String, String>? = null,
        json: JSONObject? = null,
        retry: Boolean = true
    ): String {
        val token = tokens.accessToken ?: throw ApiException(401, "Not signed in")
        val body: RequestBody? = when {
            json != null -> json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            method == "POST" -> FormBody.Builder().build()
            else -> null
        }
        val request = Request.Builder()
            .url(Config.BASE_URL + path)
            .method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", Config.USER_AGENT)
            .build()
        val (code, text) = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }
        if (code == 401 && retry && refreshTokens()) {
            return authed(method, path, form, json, retry = false)
        }
        if (code !in 200..299) throw ApiException(code, errorMessage(text, code))
        return text
    }

    private suspend fun postForm(path: String, body: RequestBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Config.BASE_URL + path)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", Config.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text, response.code))
            JSONObject(text)
        }
    }

    private fun errorMessage(text: String, code: Int): String = try {
        val json = JSONObject(text)
        json.optString("error_description")
            .ifEmpty { json.optString("error") }
            .ifEmpty { json.optString("message", "HTTP $code") }
    } catch (e: Exception) {
        "HTTP $code"
    }
}
