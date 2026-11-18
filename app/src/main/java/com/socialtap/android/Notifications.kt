package com.socialtap.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val EXTRA_CHAT_ID = "chat_id"

    const val ID_REALTIME = 1001
    const val ID_CALL_IN_PROGRESS = 1002
    const val ID_INCOMING_CALL = 1003
    private const val ID_MESSAGE_BASE = 10_000

    private const val CH_REALTIME = "realtime"
    private const val CH_MESSAGES = "messages"
    private const val CH_CALLS = "calls"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_REALTIME, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps SocialTap connected in the background"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New chat messages"
            }
        )
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Voice calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and active voice calls"
                setSound(ringtone, ringAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun realtime(context: Context): Notification =
        NotificationCompat.Builder(context, CH_REALTIME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SocialTap is connected")
            .setContentText("Messages and calls are active")
            .setContentIntent(openAppIntent(context, 0L, requestCode = 1))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    @SuppressLint("MissingPermission")
    fun showMessage(context: Context, chatId: Long, title: String, text: String, count: Int) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(count)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent(context, chatId, requestCode = 20_000 + (chatId % 10_000).toInt()))
            .build()
        NotificationManagerCompat.from(context).notify(ID_MESSAGE_BASE + (chatId % 50_000).toInt(), notification)
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCall(context: Context, callId: String, peer: String) {
        if (!canNotify(context)) return
        val fullScreen = PendingIntent.getActivity(
            context, 3,
            CallActivity.intent(context, CallActivity.ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getActivity(
            context, 4,
            CallActivity.intent(context, CallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getActivity(
            context, 5,
            CallActivity.intent(context, CallActivity.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming voice call")
            .setContentText("$peer is calling")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .build()
        NotificationManagerCompat.from(context).notify(ID_INCOMING_CALL, notification)
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_INCOMING_CALL)
    }

    fun callInProgress(context: Context, peer: String): Notification =
        NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Voice call in progress")
            .setContentText(if (peer.isBlank()) "Connected" else "With $peer")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 6,
                    CallActivity.intent(context, CallActivity.ACTION_SHOW),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun openAppIntent(context: Context, chatId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (chatId > 0) putExtra(EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canNotify(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
