package com.socialtap.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppRoot(vm: AppViewModel, onSignIn: () -> Unit, onCall: (Long) -> Unit) {
    val session by vm.session.collectAsState()
    val openChatId by vm.openChatId.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.error.value = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !session -> LoginScreen(onSignIn = onSignIn, busy = busy)
                openChatId != null -> ChatScreen(vm = vm, onCall = onCall)
                else -> ChatListScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun LoginScreen(onSignIn: () -> Unit, busy: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SocialTap", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Chats, notifications and voice calls for your SocialTap account.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignIn, enabled = !busy) {
            Text(if (busy) "Signing in…" else "Sign in with SocialTap")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "You approve access in your browser. This app never sees your password.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ChatListScreen(vm: AppViewModel) {
    val chats by vm.chats.collectAsState()
    val connected by vm.connected.collectAsState()

    LaunchedEffect(Unit) { vm.refreshChats() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (connected) "Live" else "Connecting…",
                    style = MaterialTheme.typography.bodySmall
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
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
            TextButton(
                onClick = { chatId?.let(onCall) },
                enabled = !inCall
            ) { Text("Call") }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                enabled = draft.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageItem) {
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
            Text(
                message.text,
                modifier = Modifier.padding(10.dp).widthIn(max = 300.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(message.time, style = MaterialTheme.typography.labelSmall)
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
            color = androidx.compose.ui.graphics.Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            statusLabel(state),
            style = MaterialTheme.typography.bodyLarge,
            color = androidx.compose.ui.graphics.Color(0xFFB0BEC5)
        )
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
