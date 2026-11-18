package com.socialtap.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service (microphone type) that keeps a voice call running when the app is in the background. */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra(EXTRA_PEER).orEmpty()
        val serviceType = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_CALL_IN_PROGRESS,
            Notifications.callInProgress(this, peer),
            serviceType
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_PEER = "peer"

        fun start(context: Context, peer: String) {
            try {
                val intent = Intent(context, CallService::class.java).putExtra(EXTRA_PEER, peer)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Could not start from the background; the call still works while the app is visible.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
