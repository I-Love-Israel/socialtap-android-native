package com.socialtap.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var pendingCallChatId = 0L

    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        markNotificationsAsked()
        vm.refreshNotificationState()
        if (granted) RealtimeService.start(this)
    }

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
            PackageManager.PERMISSION_GRANTED &&
            !notificationsAsked()
        ) {
            markNotificationsAsked()
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (app.tokens.isSignedIn) {
            SyncWorker.schedule(this)
        }

        handleIntent(intent)

        setContent {
            val mode by vm.themeMode.collectAsState()
            val dark = when (mode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val blocked by vm.notificationsBlocked.collectAsState()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        vm = vm,
                        onSignIn = { vm.launchSignIn(this) },
                        onGoogleSignIn = { vm.launchGoogleSignIn(this) },
                        onCall = { chatId -> requestCall(chatId) },
                        notificationsBlocked = blocked,
                        onAllowNotifications = { allowNotifications() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as SocialTapApp
        vm.refreshNotificationState()
        if (app.tokens.isSignedIn) RealtimeService.start(this)
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
        if (data != null && data.scheme == "socialtap" && data.host == "auth" && data.path == "/google") {
            vm.handleGoogleCallback(data)
            return
        }
        val chatId = intent?.getLongExtra(Notifications.EXTRA_CHAT_ID, 0L) ?: 0L
        if (chatId > 0) vm.openChat(chatId)
    }

    private fun allowNotifications() {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission && !notificationsAsked()) {
            markNotificationsAsked()
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Already asked once (or blocked in system settings): send the user to the app's notification settings.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun notificationsAsked(): Boolean =
        getSharedPreferences("ui", MODE_PRIVATE).getBoolean("notif_asked", false)

    private fun markNotificationsAsked() {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
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
