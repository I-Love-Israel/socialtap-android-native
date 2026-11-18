package com.socialtap.android

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ChatItem(val id: Long, val name: String)

data class MessageItem(
    val id: Long,
    val sender: String,
    val text: String,
    val mine: Boolean,
    val time: String
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SocialTapApp

    val session: StateFlow<Boolean> = app.session
    val call: StateFlow<CallUi> = app.calls.state
    val connected: StateFlow<Boolean> = app.realtime.connected

    val chats = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages = MutableStateFlow<List<MessageItem>>(emptyList())
    val openChatId = MutableStateFlow<Long?>(null)
    val openChatName = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            app.chatChanged.collect {
                refreshChats()
                openChatId.value?.let { loadMessages(it) }
            }
        }
    }

    fun hasMicPermission(): Boolean = app.calls.hasMicPermission()

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
                loadAccount()
                app.session.value = true
                RealtimeService.start(app)
                SyncWorker.schedule(app)
                refreshChats()
            } catch (e: Exception) {
                error.value = "Sign-in failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
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
    }

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
                app.api.sendMessage(chatId, trimmed)
                loadMessages(chatId)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun startCall(chatId: Long, onStarted: () -> Unit) {
        viewModelScope.launch {
            val problem = app.calls.startCall(chatId)
            if (problem != null) error.value = problem else onStarted()
        }
    }

    private suspend fun loadAccount() {
        try {
            val account = app.api.account()
            app.tokens.userId = account.optLong("id")
            app.tokens.username = account.optString("username")
        } catch (e: Exception) {
            // account.read may not be granted; messages still work, own messages just show as others'.
        }
    }

    private fun chatName(chat: JSONObject): String {
        val name = chat.optString("name")
        if (name.isNotBlank()) return name
        val participants = chat.optJSONArray("participants") ?: return "Chat"
        val names = (0 until participants.length()).mapNotNull { participants.optJSONObject(it)?.optString("username") }
        return names.joinToString(", ").ifBlank { "Chat" }
    }

    private fun parseMessage(json: JSONObject, me: Long): MessageItem {
        val text = when {
            json.optJSONObject("call") != null -> "Voice call"
            !json.isNull("attachment") -> "Sent an attachment"
            else -> json.optString("content")
        }
        val senderId = json.optLong("sender_user_id")
        return MessageItem(
            id = json.getLong("id"),
            sender = json.optString("username", "Someone"),
            text = text,
            mine = me != 0L && senderId == me,
            time = json.optString("timestamp")
        )
    }
}
