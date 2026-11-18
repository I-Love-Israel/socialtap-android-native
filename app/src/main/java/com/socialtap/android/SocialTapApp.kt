package com.socialtap.android

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class SocialTapApp : Application() {

    val tokens: TokenStore by lazy { TokenStore(this) }
    val api: SocialTapApi by lazy { SocialTapApi(tokens) }
    val realtime: RealtimeClient by lazy { RealtimeClient(tokens, api) }
    val sync: MessageSync by lazy { MessageSync(this) }
    val calls: CallManager by lazy { CallManager(this) }

    /** Emits when the server says a chat changed, so open screens can refresh. */
    val chatChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** True while the user has a valid session. */
    val session = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        session.value = tokens.isSignedIn
        api.onSessionEnded = {
            realtime.stop()
            RealtimeService.stop(this)
            SyncWorker.cancel(this)
            session.value = false
        }
    }
}
