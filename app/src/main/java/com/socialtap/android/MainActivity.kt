package com.socialtap.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var pendingCallChatId = 0L

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* shown later if denied */ }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val chatId = pendingCallChatId
        pendingCallChatId = 0L
        if (granted && chatId > 0) placeCall(chatId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SocialTapApp

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (app.tokens.isSignedIn) {
            RealtimeService.start(this)
            SyncWorker.schedule(this)
        }

        handleIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        vm = vm,
                        onSignIn = { vm.launchSignIn(this) },
                        onCall = { chatId -> requestCall(chatId) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "socialtap" && data.host == "oauth") {
            vm.handleCallback(data)
            return
        }
        val chatId = intent?.getLongExtra(Notifications.EXTRA_CHAT_ID, 0L) ?: 0L
        if (chatId > 0) vm.openChat(chatId)
    }

    private fun requestCall(chatId: Long) {
        if (vm.hasMicPermission()) {
            placeCall(chatId)
        } else {
            pendingCallChatId = chatId
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun placeCall(chatId: Long) {
        vm.startCall(chatId) {
            startActivity(CallActivity.intent(this, CallActivity.ACTION_SHOW))
        }
    }
}
