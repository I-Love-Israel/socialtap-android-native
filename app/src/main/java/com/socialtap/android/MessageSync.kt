package com.socialtap.android

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds new messages from other people and posts a notification for them.
 * Used by the realtime connection (on chat:changed) and by the 15-minute background worker.
 */
class MessageSync(private val app: SocialTapApp) {

    private val lock = Mutex()
    private val prefs = app.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)

    /** Chat the user has open, so its messages are not announced as notifications. -1 = none. */
    @Volatile
    var viewingChatId: Long = -1L

    suspend fun syncNow() {
        if (!app.tokens.isSignedIn) return
        lock.withLock {
            try {
                val me = app.tokens.userId
                val notifications = app.api.notifications(prefs.getLong(KEY_SINCE, 0L))
                val updates = notifications.optJSONArray("updates") ?: JSONArray()
                var names: Map<Long, String>? = null

                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    if (update.isNull("chat_id")) continue
                    if (update.optInt("new_messages", 0) <= 0) continue

                    val chatId = update.getLong("chat_id")
                    val key = KEY_CHAT_PREFIX + chatId
                    val cursor = prefs.getLong(key, -1L)
                    val messages = app.api.messages(chatId, if (cursor >= 0) cursor else 0L)
                    if (messages.length() == 0) continue

                    var newest = maxOf(cursor, 0L)
                    val fresh = mutableListOf<JSONObject>()
                    for (j in 0 until messages.length()) {
                        val message = messages.getJSONObject(j)
                        val id = message.getLong("id")
                        if (id > newest) newest = id
                        if (cursor >= 0 && id > cursor && me != 0L && message.optLong("sender_user_id") != me) {
                            fresh.add(message)
                        }
                    }
                    prefs.edit().putLong(key, newest).apply()

                    // First time we see this chat: remember the position without announcing old messages.
                    if (cursor < 0 || fresh.isEmpty() || chatId == viewingChatId) continue

                    val chatNames = names ?: loadChatNames().also { names = it }
                    val last = fresh.last()
                    val sender = last.optString("username").ifBlank { "Someone" }
                    val body = "$sender: ${describe(last)}"
                    val text = if (fresh.size > 1) "$body (+${fresh.size - 1} more)" else body
                    Notifications.showMessage(app, chatId, chatNames[chatId] ?: "SocialTap", text, fresh.size)
                }

                prefs.edit().putLong(KEY_SINCE, notifications.optLong("since", 0L)).apply()
            } catch (e: Exception) {
                // Offline or server error: the next sync (event or worker) tries again.
            }
        }
    }

    private suspend fun loadChatNames(): Map<Long, String> = try {
        val chats = app.api.chats()
        (0 until chats.length()).associate { i ->
            val chat = chats.getJSONObject(i)
            val name = chat.optString("name").ifBlank {
                val participants = chat.optJSONArray("participants") ?: JSONArray()
                (0 until participants.length())
                    .mapNotNull { participants.optJSONObject(it)?.optString("username") }
                    .joinToString(", ")
            }
            chat.getLong("id") to name
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun describe(message: JSONObject): String = when {
        message.optJSONObject("call") != null -> "Voice call"
        !message.isNull("attachment") -> "Sent an attachment"
        else -> message.optString("content").ifBlank { "New message" }
    }

    private companion object {
        const val KEY_SINCE = "since"
        const val KEY_CHAT_PREFIX = "chat_"
    }
}
