package com.socialtap.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps the realtime connection alive in the background so messages and incoming calls arrive
 * while the app is not on screen. Shows a quiet "connected" notification while it runs.
 */
class RealtimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SocialTapApp
        if (!app.tokens.isSignedIn) {
            stopSelf()
            return START_NOT_STICKY
        }
        val serviceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_REALTIME,
            Notifications.realtime(this),
            serviceType
        )

        app.realtime.start()
        if (collector == null) {
            collector = scope.launch {
                app.realtime.events.collect { event -> dispatch(app, event) }
            }
        }
        // Catch up on anything that arrived while the connection was down.
        scope.launch { app.sync.syncNow() }
        return START_STICKY
    }

    private suspend fun dispatch(app: SocialTapApp, event: RealtimeEvent) {
        val payload = event.args.optJSONObject(0) ?: JSONObject()
        when (event.name) {
            "chat:changed" -> {
                app.sync.syncNow()
                app.chatChanged.tryEmit(Unit)
            }
            "call:incoming" -> app.calls.onIncoming(payload)
            "call:accepted" -> app.calls.onAccepted(payload)
            "call:claimed" -> app.calls.onClaimed(payload)
            "call:ended" -> app.calls.onEnded(payload)
            "call:relay:start" -> app.calls.onRelayStart(payload)
            "call:audio" -> app.calls.onAudio(event.attachments.firstOrNull(), payload)
        }
    }

    override fun onDestroy() {
        collector?.cancel()
        collector = null
        (application as SocialTapApp).realtime.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
            } catch (e: Exception) {
                // Not allowed right now (app in background). It starts the next time the app is opened.
                android.util.Log.w("SocialTap", "Background connection did not start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeService::class.java))
        }
    }
}
