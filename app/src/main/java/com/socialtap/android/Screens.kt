package com.socialtap.android

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val LogoBlack = Color(0xFF000000)

@Composable
fun AppRoot(
    vm: AppViewModel,
    onSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onCall: (Long) -> Unit,
    notificationsBlocked: Boolean,
    onAllowNotifications: () -> Unit
) {
    val session by vm.session.collectAsState()
    val openChatId by vm.openChatId.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val googleEnabled by vm.googleEnabled.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.error.value = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (session && notificationsBlocked) {
                NotificationBanner(onAllowNotifications)
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    !session -> LoginScreen(
                        onSignIn = onSignIn,
                        onGoogleSignIn = onGoogleSignIn,
                        googleEnabled = googleEnabled,
                        busy = busy
                    )
                    openChatId != null -> ChatScreen(vm = vm, onCall = onCall)
                    else -> ChatListScreen(vm = vm)
                }
            }
        }
    }
}

@Composable
private fun NotificationBanner(onAllow: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Notifications are off. SocialTap cannot show its background connection or new messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun LoginScreen(
    onSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit,
    googleEnabled: Boolean,
    busy: Boolean
) {
    Column(Modifier.fillMaxSize()) {
        // Cover: the SocialTap logo on black, the same in light and dark mode.
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(LogoBlack),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.socialtap_logo),
                contentDescription = "SocialTap logo",
                modifier = Modifier.size(170.dp),
                contentScale = ContentScale.Fit
            )
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Welcome to SocialTap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Chats, notifications, files and voice calls for your SocialTap account.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (googleEnabled) {
                Button(onClick = onGoogleSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Signing in…" else "Sign in with Google")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in with SocialTap")
                }
            } else {
                Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Signing in…" else "Sign in with SocialTap")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "You approve access in your browser. This app never sees your password.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChatListScreen(vm: AppViewModel) {
    val chats by vm.chats.collectAsState()
    val connected by vm.connected.collectAsState()
    val theme by vm.themeMode.collectAsState()
    val blocked by vm.notificationsBlocked.collectAsState()

    LaunchedEffect(Unit) { vm.refreshChats() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        blocked -> "Background connection hidden (notifications off)"
                        connected -> "Live"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { vm.cycleTheme() }) {
                Text(
                    when (theme) {
                        "light" -> "Light"
                        "dark" -> "Dark"
                        else -> "Auto"
                    }
                )
            }
            TextButton(onClick = { vm.refreshChats() }) { Text("Refresh") }
            TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
        }
        HorizontalDivider()
        if (chats.isEmpty()) {
            Text("No chats yet.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(chats, key = { it.id }) { chat ->
                Text(
                    chat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openChat(chat.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: AppViewModel, onCall: (Long) -> Unit) {
    val chatId by vm.openChatId.collectAsState()
    val name by vm.openChatName.collectAsState()
    val messages by vm.messages.collectAsState()
    val call by vm.call.collectAsState()
    val busy by vm.busy.collectAsState()
    var draft by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<AttachmentItem?>(null) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.sendAttachment(uri, draft)
            draft = ""
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.closeChat() }) { Text("Back") }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            val inCall = call.phase != CallPhase.IDLE && call.phase != CallPhase.ENDED
            TextButton(onClick = { chatId?.let(onCall) }, enabled = !inCall) { Text("Call") }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(vm = vm, message = message, onPreviewImage = { preview = it })
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { picker.launch("*/*") }, enabled = !busy) { Text("Attach") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.send(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && !busy
            ) { Text("Send") }
        }
    }

    preview?.let { attachment ->
        ImagePreviewDialog(vm = vm, attachment = attachment, onDismiss = { preview = null })
    }
}

/** Own messages sit on the right; other people's on the left. */
@Composable
private fun MessageBubble(vm: AppViewModel, message: MessageItem, onPreviewImage: (AttachmentItem) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start
    ) {
        Text(
            if (message.mine) "You" else message.sender,
            style = MaterialTheme.typography.labelSmall
        )
        Surface(
            color = if (message.mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.widthIn(max = 280.dp).padding(8.dp)) {
                message.attachment?.let { AttachmentContent(vm, it, onPreviewImage) }
                if (message.text.isNotBlank()) {
                    Text(
                        message.text,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Text(message.time, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AttachmentContent(vm: AppViewModel, attachment: AttachmentItem, onPreviewImage: (AttachmentItem) -> Unit) {
    val reason = attachment.unavailableReason
    when {
        reason != null -> Text(reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
        attachment.isImage -> {
            val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, attachment.path) {
                value = vm.loadImage(attachment.path)
            }
            val loaded = image
            if (loaded != null) {
                Image(
                    bitmap = loaded,
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPreviewImage(attachment) }
                )
            } else {
                Text("Loading image…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            }
        }
        else -> FileCard(vm, attachment)
    }
}

@Composable
private fun FileCard(vm: AppViewModel, attachment: AttachmentItem) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.widthIn(min = 180.dp).clickable {
            scope.launch {
                val intent = vm.openFileIntent(attachment)
                if (intent != null) {
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        vm.error.value = "No app on this phone can open ${attachment.filename}"
                    }
                }
            }
        }
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(attachment.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatSize(attachment.size)} · tap to open",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ImagePreviewDialog(vm: AppViewModel, attachment: AttachmentItem, onDismiss: () -> Unit) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, attachment.path) {
        value = vm.loadImage(attachment.path)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(LogoBlack).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            val loaded = image
            if (loaded != null) {
                Image(
                    bitmap = loaded,
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Loading…", color = Color.White)
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Close") }
        }
    }
}

@Composable
fun CallScreen(
    state: CallUi,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            state.peerName.ifBlank { "Voice call" },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(statusLabel(state), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB0BEC5))
        Spacer(Modifier.height(40.dp))
        when (state.phase) {
            CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Decline") }
                Button(onClick = onAnswer) { Text("Answer") }
            }
            CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.ACTIVE -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.phase == CallPhase.ACTIVE) {
                    OutlinedButton(onClick = onMute) {
                        Text(if (state.muted) "Unmute" else "Mute")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Button(
                    onClick = onHangUp,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (state.phase == CallPhase.OUTGOING) "Cancel" else "Hang up")
                }
            }
            else -> Unit
        }
    }
}

private fun statusLabel(state: CallUi): String = when {
    state.message.isNotBlank() -> state.message
    state.phase == CallPhase.INCOMING -> "Incoming voice call"
    state.phase == CallPhase.OUTGOING -> "Ringing"
    state.phase == CallPhase.CONNECTING -> "Connecting"
    state.phase == CallPhase.ACTIVE -> "Connected"
    else -> ""
}
