package com.socialtap.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen call UI: ringing, answering, and the active call. Shown over the lock screen. */
class CallActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val app = application as SocialTapApp
        if (granted) {
            lifecycleScope.launch { app.calls.accept() }
        } else {
            app.calls.decline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        handleAction(intent)

        setContent {
            val app = application as SocialTapApp
            val state by app.calls.state.collectAsState()
            LaunchedEffect(state.phase) {
                if (state.phase == CallPhase.ENDED || state.phase == CallPhase.IDLE) {
                    delay(1_500L)
                    finish()
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101418))) {
                    CallScreen(
                        state = state,
                        onAnswer = { answer() },
                        onDecline = { app.calls.decline() },
                        onHangUp = { app.calls.hangUp() },
                        onMute = { app.calls.toggleMute() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        val app = application as SocialTapApp
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_ANSWER -> answer()
            ACTION_DECLINE -> app.calls.decline()
        }
    }

    private fun answer() {
        val app = application as SocialTapApp
        if (app.calls.hasMicPermission()) {
            lifecycleScope.launch { app.calls.accept() }
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val ACTION_SHOW = "show"
        const val ACTION_ANSWER = "answer"
        const val ACTION_DECLINE = "decline"
        private const val EXTRA_ACTION = "action"

        fun intent(context: Context, action: String): Intent =
            Intent(context, CallActivity::class.java)
                .putExtra(EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
