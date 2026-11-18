package com.socialtap.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

enum class CallPhase { IDLE, INCOMING, OUTGOING, CONNECTING, ACTIVE, ENDED }

data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val chatId: Long = 0L,
    val peerName: String = "",
    val muted: Boolean = false,
    val message: String = ""
)

data class RelayFormat(val sampleRate: Int, val frameSamples: Int) {
    companion object {
        fun from(json: JSONObject?): RelayFormat? {
            if (json == null) return null
            return RelayFormat(
                sampleRate = json.optInt("sample_rate", 8000),
                frameSamples = json.optInt("frame_samples", 400)
            )
        }
    }
}

/**
 * One-to-one voice calls through SocialTap's server relay (Socket.IO events call:* and binary call:audio).
 * Audio is mono IMA ADPCM in 50 ms packets, matching the web client.
 */
class CallManager(private val app: SocialTapApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CallUi())
    val state: StateFlow<CallUi> = _state.asStateFlow()

    private val playback = Executors.newSingleThreadExecutor()
    private var relay: RelayFormat? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    private var recordJob: Job? = null
    private var ringTimeout: Job? = null
    private var pingJob: Job? = null
    @Volatile private var muted = false

    private val realtime get() = app.realtime

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Caller side. Returns an error message, or null when the call is ringing. */
    suspend fun startCall(chatId: Long): String? {
        if (_state.value.phase != CallPhase.IDLE) return "You are already in a call"
        if (!hasMicPermission()) return "Microphone permission is needed for calls"
        if (!realtime.connected.value) return "Not connected yet. Try again in a moment."
        return try {
            val res = realtime.request("call:start", JSONObject().put("chat_id", chatId))
            relay = RelayFormat.from(res.optJSONObject("relay_audio"))
            _state.value = CallUi(
                phase = CallPhase.OUTGOING,
                callId = res.getString("call_id"),
                chatId = chatId,
                peerName = res.optString("username"),
                message = "Ringing"
            )
            null
        } catch (e: Exception) {
            e.message ?: "Could not start the call"
        }
    }

    /** Callee side: the user tapped Answer. Returns an error message, or null when connected. */
    suspend fun accept(): String? {
        val current = _state.value
        val callId = current.callId
        if (current.phase != CallPhase.INCOMING || callId == null) return "No incoming call"
        if (!hasMicPermission()) return "Microphone permission is needed for calls"
        return try {
            val res = realtime.request("call:accept", JSONObject().put("call_id", callId))
            RelayFormat.from(res.optJSONObject("relay_audio"))?.let { relay = it }
            ringTimeout?.cancel()
            Notifications.cancelIncoming(app)
            activate(callId, current.chatId, current.peerName)
            null
        } catch (e: Exception) {
            reset("Could not answer: ${e.message}")
            e.message
        }
    }

    fun decline() {
        val current = _state.value
        if (current.phase == CallPhase.INCOMING && current.callId != null) {
            realtime.emit("call:end", JSONObject().put("call_id", current.callId).put("reason", "declined"))
            reset("Call declined")
        } else {
            // Nothing live to decline (for example after a process restart): just clear the ringing notification.
            Notifications.cancelIncoming(app)
        }
    }

    fun hangUp() {
        val current = _state.value
        val callId = current.callId ?: return
        realtime.emit("call:end", JSONObject().put("call_id", callId).put("reason", "ended"))
        reset("Call ended")
    }

    fun toggleMute() {
        muted = !muted
        _state.value = _state.value.copy(muted = muted)
    }

    // ---- Events from the realtime connection -------------------------------------------------

    fun onIncoming(payload: JSONObject) {
        val callId = payload.optString("call_id")
        if (callId.isEmpty()) return
        if (_state.value.phase != CallPhase.IDLE) {
            realtime.emit("call:end", JSONObject().put("call_id", callId).put("reason", "declined"))
            return
        }
        relay = RelayFormat.from(payload.optJSONObject("relay_audio")) ?: relay
        val peer = payload.optString("username")
        _state.value = CallUi(
            phase = CallPhase.INCOMING,
            callId = callId,
            chatId = payload.optLong("chat_id"),
            peerName = peer,
            message = "Incoming call"
        )
        Notifications.showIncomingCall(app, callId, peer)
        ringTimeout?.cancel()
        ringTimeout = scope.launch {
            delay(50_000L)
            if (_state.value.callId == callId && _state.value.phase == CallPhase.INCOMING) {
                reset("Missed call")
            }
        }
    }

    /** The other person accepted our outgoing call. */
    fun onAccepted(payload: JSONObject) {
        val current = _state.value
        if (current.phase != CallPhase.OUTGOING || current.callId != payload.optString("call_id")) return
        activate(current.callId, current.chatId, current.peerName)
    }

    /** Another of our devices answered this incoming call. */
    fun onClaimed(payload: JSONObject) {
        val current = _state.value
        if (current.phase == CallPhase.INCOMING &&
            current.callId == payload.optString("call_id") &&
            payload.optString("sid") != realtime.sid
        ) {
            reset("Answered on another device")
        }
    }

    fun onEnded(payload: JSONObject) {
        val current = _state.value
        if (current.phase == CallPhase.IDLE || current.callId != payload.optString("call_id")) return
        val message = when (payload.optString("reason")) {
            "declined" -> "Call declined"
            "no_answer" -> "No answer"
            "connection_issue" -> "Connection lost"
            else -> "Call ended"
        }
        reset(message)
    }

    fun onRelayStart(payload: JSONObject) {
        val current = _state.value
        val active = current.phase == CallPhase.CONNECTING || current.phase == CallPhase.ACTIVE
        if (active && current.callId == payload.optString("call_id") && recorder == null) {
            startAudio()
        }
    }

    fun onAudio(frame: ByteArray?, payload: JSONObject) {
        if (frame == null) return
        val current = _state.value
        if (current.callId != payload.optString("call_id")) return
        if (current.phase != CallPhase.ACTIVE && current.phase != CallPhase.CONNECTING) return
        val format = relay ?: return
        val pcm = AdpcmCodec.decode(frame, format.frameSamples) ?: return
        playback.execute { track?.write(pcm, 0, pcm.size) }
    }

    // ---- Internals ---------------------------------------------------------------------------

    private fun activate(callId: String, chatId: Long, peer: String) {
        _state.value = CallUi(CallPhase.CONNECTING, callId, chatId, peer, muted, "Connecting")
        CallService.start(app, peer)
        realtime.emit("call:relay:start", JSONObject().put("call_id", callId))
        val audioOk = startAudio()
        realtime.emit("call:connected", JSONObject().put("call_id", callId))
        _state.value = _state.value.copy(
            phase = CallPhase.ACTIVE,
            message = if (audioOk) "Connected" else "Connected, but the microphone could not start"
        )
        startPings(callId)
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        if (recorder != null) return true
        val format = relay ?: return false
        if (!hasMicPermission()) return false
        val rate = format.sampleRate
        val frame = format.frameSamples
        return try {
            val recordMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(recordMin, frame * 2 * 4)
            )
            val playMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(playMin, frame * 2 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            rec.startRecording()
            trk.play()
            recorder = rec
            track = trk
            recordJob = scope.launch {
                val pcm = ShortArray(frame)
                while (isActive) {
                    var filled = 0
                    while (filled < frame) {
                        val read = rec.read(pcm, filled, frame - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    if (filled < frame) break
                    if (!muted) {
                        val callId = _state.value.callId ?: break
                        realtime.emitBinary("call:audio", JSONObject().put("call_id", callId), AdpcmCodec.encode(pcm))
                    }
                }
            }
            true
        } catch (e: Exception) {
            stopAudio()
            false
        }
    }

    private fun stopAudio() {
        recordJob?.cancel()
        recordJob = null
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun startPings(callId: String) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(10_000L)
                realtime.emit("call:ping", JSONObject().put("call_id", callId))
            }
        }
    }

    private fun reset(message: String) {
        stopAudio()
        pingJob?.cancel()
        ringTimeout?.cancel()
        relay = null
        Notifications.cancelIncoming(app)
        CallService.stop(app)
        _state.value = CallUi(phase = CallPhase.ENDED, message = message, muted = muted)
        scope.launch {
            delay(2_000L)
            if (_state.value.phase == CallPhase.ENDED) _state.value = CallUi()
        }
    }
}
