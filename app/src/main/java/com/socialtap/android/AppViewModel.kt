package com.socialtap.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ChatItem(val id: Long, val name: String)

data class MessageItem(
    val id: Long,
    val sender: String,
    val text: String,
    val mine: Boolean,
    val time: String,
    val attachment: AttachmentItem? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SocialTapApp
    private val prefs = application.getSharedPreferences("ui", Context.MODE_PRIVATE)

    val session: StateFlow<Boolean> = app.session
    val call: StateFlow<CallUi> = app.calls.state
    val connected: StateFlow<Boolean> = app.realtime.connected

    val chats = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages = MutableStateFlow<List<MessageItem>>(emptyList())
    val openChatId = MutableStateFlow<Long?>(null)
    val openChatName = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val googleEnabled = MutableStateFlow(false)

    /** "system", "light" or "dark". */
    val themeMode = MutableStateFlow(prefs.getString("theme", "system") ?: "system")

    /** True when Android hides SocialTap notifications, so the background connection is not visible. */
    val notificationsBlocked = MutableStateFlow(false)

    init {
        refreshNotificationState()
        viewModelScope.launch { googleEnabled.value = app.api.googleSignInEnabled() }
        viewModelScope.launch {
            app.chatChanged.collect {
                refreshChats()
                openChatId.value?.let { loadMessages(it) }
            }
        }
    }

    fun hasMicPermission(): Boolean = app.calls.hasMicPermission()

    fun refreshNotificationState() {
        notificationsBlocked.value = app.tokens.isSignedIn && !Notifications.allowed(app)
    }

    fun cycleTheme() {
        val next = when (themeMode.value) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        themeMode.value = next
        prefs.edit().putString("theme", next).apply()
    }

    // ---- Sign-in -----------------------------------------------------------------------------

    /** Opens SocialTap's sign-in and consent page in a Custom Tab (PKCE, S256). */
    fun launchSignIn(context: Context) {
        val verifier = Pkce.randomString(48)
        val state = Pkce.randomString(32)
        app.tokens.savePending(verifier, state)
        val scopes = (Config.REQUIRED_SCOPES + Config.OPTIONAL_SCOPES).joinToString(" ")
        val uri = Uri.parse("${Config.BASE_URL}/api/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", Config.OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", Pkce.challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }

    /** Starts Google sign-in in the browser; SocialTap returns to socialtap://auth/google. */
    fun launchGoogleSignIn(context: Context) {
        val verifier = Pkce.randomString(48)
        val state = Pkce.randomString(32)
        app.tokens.savePendingGoogle(verifier, state)
        val uri = Uri.parse("${Config.BASE_URL}/google_login").buildUpon()
            .appendQueryParameter("mobile", "android")
            .appendQueryParameter("app_state", state)
            .appendQueryParameter("code_challenge", Pkce.challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }

    /** socialtap://auth/google?state=...&code=... (or error=access_denied). */
    fun handleGoogleCallback(uri: Uri) {
        val denied = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val verifier = app.tokens.pendingGoogleVerifier
        val expectedState = app.tokens.pendingGoogleState
        app.tokens.clearPendingGoogle()

        viewModelScope.launch {
            if (denied != null) {
                error.value = "Google sign-in was cancelled."
                return@launch
            }
            if (code == null || verifier == null || returnedState == null || returnedState != expectedState) {
                error.value = "That Google sign-in response did not match this app. Please try again."
                return@launch
            }
            busy.value = true
            try {
                app.browserFlow.exchangeGoogleHandoff(code, verifier)
                app.browserFlow.authorizeWithSession()
                finishSignIn()
            } catch (e: Exception) {
                error.value = e.message ?: "Google sign-in failed"
            } finally {
                busy.value = false
            }
        }
    }

    /** socialtap://oauth/callback?code=...&state=... from the browser. */
    fun handleCallback(uri: Uri) {
        val denied = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val verifier = app.tokens.pendingVerifier
        val expectedState = app.tokens.pendingState
        app.tokens.clearPending()

        viewModelScope.launch {
            if (denied != null) {
                error.value = "Sign-in was not approved ($denied)."
                return@launch
            }
            if (code == null || verifier == null || returnedState == null || returnedState != expectedState) {
                error.value = "That sign-in response did not match this app. Please try again."
                return@launch
            }
            busy.value = true
            try {
                app.api.exchangeCode(code, verifier)
                finishSignIn()
            } catch (e: Exception) {
                error.value = "Sign-in failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun finishSignIn() {
        loadAccount()
        app.session.value = true
        RealtimeService.start(app)
        SyncWorker.schedule(app)
        refreshNotificationState()
        refreshChats()
    }

    fun signOut() {
        app.realtime.stop()
        RealtimeService.stop(app)
        SyncWorker.cancel(app)
        app.tokens.clear()
        app.session.value = false
        chats.value = emptyList()
        messages.value = emptyList()
        closeChat()
        refreshNotificationState()
    }

    // ---- Chats and messages ------------------------------------------------------------------

    fun refreshChats() {
        viewModelScope.launch {
            try {
                val list = app.api.chats()
                chats.value = (0 until list.length()).map { i ->
                    val chat = list.getJSONObject(i)
                    ChatItem(chat.getLong("id"), chatName(chat))
                }
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun openChat(chatId: Long) {
        openChatId.value = chatId
        openChatName.value = chats.value.firstOrNull { it.id == chatId }?.name ?: "Chat"
        app.sync.viewingChatId = chatId
        messages.value = emptyList()
        loadMessages(chatId)
    }

    fun closeChat() {
        openChatId.value = null
        app.sync.viewingChatId = -1L
    }

    fun loadMessages(chatId: Long) {
        viewModelScope.launch {
            try {
                if (app.tokens.userId == 0L) loadAccount()
                val list = app.api.messages(chatId)
                val me = app.tokens.userId
                messages.value = (0 until list.length()).map { i -> parseMessage(list.getJSONObject(i), me) }
                val newest = list.optJSONObject(list.length() - 1)?.optLong("id") ?: 0L
                if (newest > 0) app.api.markRead(chatId, newest)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun send(text: String) {
        val chatId = openChatId.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val sent = app.api.sendMessage(chatId, trimmed)
                learnMyId(sent)
                loadMessages(chatId)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    /** Sends a picked file (max 10 MiB) to the open chat, with the typed text as its caption. */
    fun sendAttachment(uri: Uri, caption: String) {
        val chatId = openChatId.value ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val (name, declaredSize) = displayNameAndSize(uri)
                if (declaredSize > MAX_UPLOAD_BYTES) throw IOException("That file is larger than 10 MB")
                val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IOException("Could not read that file")
                if (bytes.size > MAX_UPLOAD_BYTES) throw IOException("That file is larger than 10 MB")
                val sent = app.api.uploadAttachment(chatId, name, mime, bytes, caption.trim())
                learnMyId(sent)
                loadMessages(chatId)
            } catch (e: Exception) {
                error.value = e.message ?: "Could not send that file"
            } finally {
                busy.value = false
            }
        }
    }

    /** Full-size image bytes for the in-app preview (or thumbnails). */
    suspend fun loadImage(path: String): ImageBitmap? = try {
        ImageCache.load(app, path)
    } catch (e: Exception) {
        null
    }

    /** Downloads a file into the app cache and returns an intent that opens it in another app. */
    suspend fun openFileIntent(attachment: AttachmentItem): Intent? = try {
        val bytes = app.api.downloadBytes(attachment.path)
        val dir = File(app.cacheDir, "attachments").apply { mkdirs() }
        val safeName = attachment.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, safeName)
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (e: Exception) {
        error.value = e.message ?: "Could not open that file"
        null
    }

    fun startCall(chatId: Long, onStarted: () -> Unit) {
        viewModelScope.launch {
            val problem = app.calls.startCall(chatId)
            if (problem != null) error.value = problem else onStarted()
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private suspend fun loadAccount() {
        try {
            val account = app.api.account()
            app.tokens.userId = account.optLong("id")
            app.tokens.username = account.optString("username")
        } catch (e: Exception) {
            // account.read may not be granted; messages still work, but own messages can show on the left
            // until the first message is sent (the send response tells us our id).
        }
    }

    private fun learnMyId(sentMessage: JSONObject) {
        val senderId = sentMessage.optLong("sender_user_id")
        if (app.tokens.userId == 0L && senderId > 0) app.tokens.userId = senderId
    }

    private fun displayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                }
            }
        return name to size
    }

    private fun chatName(chat: JSONObject): String {
        val name = chat.optString("name")
        if (name.isNotBlank()) return name
        val participants = chat.optJSONArray("participants") ?: return "Chat"
        val names = (0 until participants.length()).mapNotNull { participants.optJSONObject(it)?.optString("username") }
        return names.joinToString(", ").ifBlank { "Chat" }
    }

    private fun parseMessage(json: JSONObject, me: Long): MessageItem {
        val attachment = AttachmentItem.from(json.optJSONObject("attachment"))
        val text = when {
            json.optJSONObject("call") != null -> "Voice call"
            attachment != null && json.optString("content").isBlank() -> ""
            else -> json.optString("content")
        }
        val senderId = json.optLong("sender_user_id")
        return MessageItem(
            id = json.getLong("id"),
            sender = json.optString("username", "Someone"),
            text = text,
            mine = me != 0L && senderId == me,
            time = json.optString("timestamp"),
            attachment = attachment
        )
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024
    }
}
