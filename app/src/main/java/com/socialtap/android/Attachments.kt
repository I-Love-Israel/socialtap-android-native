package com.socialtap.android

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

/** A file attached to a message. [unavailableReason] is set when the bytes can no longer be fetched. */
data class AttachmentItem(
    val path: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val unavailableReason: String?
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")

    companion object {
        fun from(json: JSONObject?): AttachmentItem? {
            if (json == null) return null
            val path = json.optString("url").takeIf { it.startsWith("/attachments/") }
            val reason = when {
                json.optBoolean("removed") -> "This file was removed"
                json.optBoolean("restricted") -> "This file is restricted"
                json.optBoolean("expired") || path == null -> "This file expired"
                else -> null
            }
            return AttachmentItem(
                path = path ?: "",
                filename = json.optString("filename", "file"),
                mimeType = json.optString("mimetype", "application/octet-stream"),
                size = json.optLong("size", 0L),
                unavailableReason = reason
            )
        }
    }
}

/** Decoded image previews, kept in memory only (bytes are private and need the bearer token). */
object ImageCache {
    private val cache = LruCache<String, ImageBitmap>(40)

    suspend fun load(app: SocialTapApp, path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bytes = app.api.downloadBytes(path)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val image = bitmap.asImageBitmap()
        cache.put(path, image)
        return image
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
