package com.newstar.live

import android.app.Activity
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object NativeGoogleOAuth {
    private const val SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
    private const val REDIRECT_PAGE = "https://new-star-delta.vercel.app/profile.html"

    fun launch(activity: Activity) {
        val random = SecureRandom()
        val verifierBytes = ByteArray(32).also(random::nextBytes)
        val verifier = Base64.encodeToString(
            verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val state = UUID.randomUUID().toString()
        NativeSessionStore.savePendingOAuth(activity, state, verifier)

        val redirect = Uri.parse(REDIRECT_PAGE).buildUpon()
            .appendQueryParameter("native_oauth", "1")
            .appendQueryParameter("oauth_state", state)
            .appendQueryParameter("code_verifier", verifier)
            .build()
        val authorize = Uri.parse("$SUPABASE_URL/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", redirect.toString())
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .appendQueryParameter("prompt", "select_account")
            .build()
        CustomTabsIntent.Builder().build().launchUrl(activity, authorize)
    }
}
