package com.socialtap.android

object Config {
    const val BASE_URL = "https://www.socialtap.social"
    const val WS_HOST = "www.socialtap.social"

    /**
     * Public OAuth client ID from the SocialTap developer dashboard
     * (Profile -> Developer dashboard). Public clients use PKCE and have no client secret,
     * so this value is safe to ship inside the app.
     */
    const val OAUTH_CLIENT_ID = "stu_A49-g2KYxxXPJQL-OfuawbvqF5FARwCx5dsT7zGqhD0"
    const val REDIRECT_URI = "socialtap://oauth/callback"

    const val USER_AGENT = "SocialTap-Android/1.0.0"

    /** Must match the scopes configured for the client in the dashboard. */
    val REQUIRED_SCOPES = listOf("chat.read", "chat.send", "voice.call", "voice.audio")
    val OPTIONAL_SCOPES = listOf("account.read", "friends.read")
}
