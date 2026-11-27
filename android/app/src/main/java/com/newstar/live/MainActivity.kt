package com.newstar.live

import android.content.Context
import android.content.Intent
import android.app.ProgressDialog
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val api = NewStarApi()
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("newstar_native_session", Context.MODE_PRIVATE) }
    private var session: NewStarSession? = null
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private var runtimeConfig = JSONObject()
    private var lastRuntimeConfigFetchAt = 0L
    private var runtimeConfigFetchInFlight = false
    private var activeScreen = "Auth"

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            promptVideoTitle(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 10, 18)
        window.navigationBarColor = Color.rgb(8, 10, 18)
        session = restoreSession()
        prefs.getString("runtime_config_json", null)?.let { cached ->
            runCatching { runtimeConfig = JSONObject(cached) }
        }
        if (session == null) {
            showAuth()
        } else {
            // Keep the creator signed in across app restarts; refresh tokens silently.
            showHome()
            refreshSavedSession()
        }
        handleSupabaseOAuthCallback(intent)
        loadRuntimeConfig(force = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSupabaseOAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        loadRuntimeConfig()
    }

    private fun loadRuntimeConfig(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (runtimeConfigFetchInFlight || (!force && now - lastRuntimeConfigFetchAt < 5 * 60 * 1000L)) return
        runtimeConfigFetchInFlight = true
        lastRuntimeConfigFetchAt = now
        worker.execute {
            try {
                val remote = api.loadRuntimeConfig()
                runOnUiThread {
                    runtimeConfigFetchInFlight = false
                    val previousConfig = runtimeConfig.toString()
                    runtimeConfig = remote.config
                    prefs.edit()
                        .putString("runtime_config_json", remote.config.toString())
                        .putLong("runtime_config_version", remote.version)
                        .apply()
                    if (previousConfig != runtimeConfig.toString()) refreshVisibleScreenForRemoteFlags()
                    showRuntimeConfigNotice(remote.config, remote.version)
                }
            } catch (_: Exception) {
                runtimeConfigFetchInFlight = false
            }
        }
    }

    private fun showRuntimeConfigNotice(config: JSONObject, version: Long) {
        if (isFinishing || isDestroyed) return
        val shownVersion = prefs.getLong("runtime_notice_version", -1L)
        if (version <= shownVersion) return
        val maintenance = config.optBoolean("maintenance_mode", false)
        val announcementEnabled = config.optBoolean("announcement_enabled", false)
        val announcement = config.optString("announcement").trim()
        val message = if (maintenance) {
            config.optString("maintenance_message").ifBlank { "New Star is temporarily under maintenance. Please try again soon." }
        } else if (announcementEnabled && announcement.isNotBlank()) {
            announcement
        } else {
            prefs.edit().putLong("runtime_notice_version", version).apply()
            return
        }
        prefs.edit().putLong("runtime_notice_version", version).apply()
        AlertDialog.Builder(this)
            .setTitle(if (maintenance) "New Star maintenance" else "New Star announcement")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun isRemoteFeatureEnabled(name: String, defaultValue: Boolean = true): Boolean {
        return runtimeConfig.optJSONObject("feature_flags")?.optBoolean(name, defaultValue) ?: defaultValue
    }

    private fun refreshVisibleScreenForRemoteFlags() {
        if (isFinishing || isDestroyed) return
        when (activeScreen) {
            "Home" -> showHome()
            "Search" -> if (isRemoteFeatureEnabled("show_search", true)) showSearch() else showHome()
            "Profile" -> if (isRemoteFeatureEnabled("show_profile", true)) showProfile() else showHome()
        }
    }

    private fun restoreSession(): NewStarSession? {
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        val id = prefs.getString("user_id", null) ?: return null
        return NewStarSession(access, refresh, id, prefs.getString("email", "") ?: "")
    }

    private fun saveSession(value: NewStarSession) {
        session = value
        prefs.edit().putString("access", value.accessToken).putString("refresh", value.refreshToken).putString("user_id", value.userId).putString("email", value.email).apply()
    }

    private fun refreshSavedSession() {
        val saved = session ?: return
        worker.execute {
            try {
                val refreshed = api.refreshSession(saved.refreshToken)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && session?.userId == saved.userId) {
                        saveSession(refreshed)
                    }
                }
            } catch (e: Exception) {
                val message = e.message.orEmpty().lowercase()
                val refreshRejected = message.contains("invalid_grant") ||
                    message.contains("refresh_token_not_found") ||
                    message.contains("invalid refresh token") ||
                    message.contains("refresh token not found")
                if (refreshRejected) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && session?.userId == saved.userId) {
                            prefs.edit().remove("access").remove("refresh").remove("user_id").remove("email").apply()
                            session = null
                            showAuth()
                            showStatus("आपका लॉगिन सत्र समाप्त हो गया है। कृपया एक बार फिर लॉगिन करें।")
                        }
                    }
                }
                // For temporary network/server errors, keep the saved session and home screen.
            }
        }
    }

    private fun baseScreen(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 10, 18)) }
        val header = TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); setTypeface(null, 1) }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE }
        root.addView(status)
        setContentView(root)
    }

    private fun showAuth() {
        activeScreen = "Auth"
        baseScreen("✦ New Star")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(20), dp(24), dp(20)) }
        panel.addView(label("Welcome to New Star", 26f, true))
        panel.addView(label("Sign in to watch and connect", 14f))
        val email = EditText(this).apply { hint = "Email address"; setSingleLine(); inputType = 33; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setPadding(dp(14), 0, dp(14), 0); setBackgroundColor(Color.rgb(28, 31, 43)) }
        val password = EditText(this).apply { hint = "Password"; setSingleLine(); inputType = 129; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setPadding(dp(14), 0, dp(14), 0); setBackgroundColor(Color.rgb(28, 31, 43)) }
        val signIn = button("Sign in"); val signUp = button("Create account")
        val googleSignIn = button("Continue with Google")
        panel.addView(email, LinearLayout.LayoutParams(-1, dp(52))); panel.addView(space(12))
        panel.addView(password, LinearLayout.LayoutParams(-1, dp(52))); panel.addView(space(18))
        panel.addView(signIn, LinearLayout.LayoutParams(-1, dp(50))); panel.addView(space(8))
        panel.addView(signUp, LinearLayout.LayoutParams(-1, dp(50))); panel.addView(space(14))
        panel.addView(label("OR", 13f)); panel.addView(space(8))
        panel.addView(googleSignIn, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(ScrollView(this).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
        signIn.setOnClickListener { authenticate(email.text.toString(), password.text.toString(), false, signIn) }
        signUp.setOnClickListener { authenticate(email.text.toString(), password.text.toString(), true, signUp) }
        googleSignIn.setOnClickListener { signInWithGoogle(googleSignIn) }
    }

    private fun signInWithGoogle(trigger: Button) {
        trigger.isEnabled = false
        try {
            val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val redirectUri = Uri.parse("newstar://auth/callback")
            prefs.edit()
                .putString("oauth_pkce_verifier", verifier)
                .apply()
            val authorizeUri = Uri.parse(NewStarApi.BASE_URL + "/auth/v1/authorize").buildUpon()
                .appendQueryParameter("provider", "google")
                .appendQueryParameter("redirect_to", redirectUri.toString())
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "s256")
                .build()
            showStatus("Supabase के जरिए Google Login खोला जा रहा है…")
            startActivity(Intent(Intent.ACTION_VIEW, authorizeUri))
            trigger.isEnabled = true
        } catch (e: Exception) {
            trigger.isEnabled = true
            showStatus(e.message ?: "Google Login शुरू नहीं हो पाया।")
        }
    }

    private fun handleSupabaseOAuthCallback(callbackIntent: Intent?) {
        val uri = callbackIntent?.data ?: return
        if (uri.scheme != "newstar" || uri.host != "auth" || uri.path != "/callback") return
        val error = uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            prefs.edit().remove("oauth_pkce_verifier").apply()
            showStatus("Google Login पूरा नहीं हुआ: " + error)
            return
        }
        val code = uri.getQueryParameter("code")
        val verifier = prefs.getString("oauth_pkce_verifier", null)
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            prefs.edit().remove("oauth_pkce_verifier").apply()
            showStatus("Google Login callback सत्यापित नहीं हो पाया। कृपया दोबारा कोशिश करें।")
            return
        }
        prefs.edit().remove("oauth_pkce_verifier").apply()
        showStatus("Supabase Login session सुरक्षित रूप से बनाया जा रहा है…")
        worker.execute {
            try {
                val signedIn = api.exchangeOAuthCode(code, verifier)
                runOnUiThread {
                    saveSession(signedIn)
                    showHome()
                }
            } catch (e: Exception) {
                runOnUiThread { showStatus(e.message ?: "Google Login session नहीं बन पाया।") }
            }
        }
    }

    private fun authenticate(email: String, password: String, create: Boolean, trigger: Button) {
        if (!email.contains("@") || password.length < 6) { showStatus("Enter a valid email and password of at least 6 characters."); return }
        trigger.isEnabled = false; showStatus(if (create) "Creating account…" else "Signing in…")
        worker.execute {
            try {
                val result = if (create) api.signUp(email, password) else api.signIn(email, password)
                runOnUiThread {
                    trigger.isEnabled = true
                    if (result == null) showStatus("Account created. Verify your email, then sign in.")
                    else { saveSession(result); showHome() }
                }
            } catch (e: Exception) { runOnUiThread { trigger.isEnabled = true; showStatus(e.message ?: "Authentication failed.") } }
        }
    }

    private fun showHome() {
        activeScreen = "Home"
        val remoteTitle = runtimeConfig.optJSONObject("ui")?.optString("home_title").orEmpty().ifBlank { "✦ New Star" }
        baseScreen(remoteTitle)
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val forYou = button("For You")
        val following = button("Following")
        val refresh = TextView(this).apply { text = "↻"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        toolbar.addView(forYou, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(following, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(44)))
        content.addView(toolbar, FrameLayout.LayoutParams(-1, dp(48)))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(48) })
        forYou.setOnClickListener { loadFeed(list, false) }
        following.setOnClickListener { loadFeed(list, true) }
        refresh.setOnClickListener { loadFeed(list, false) }
        loadFeed(list, false)
        addBottomNav("Home")
    }

    private fun loadFeed(list: LinearLayout, followingOnly: Boolean = false) {
        list.removeAllViews(); list.addView(label(if (followingOnly) "Loading followed creators…" else "Loading videos…", 16f))
        worker.execute {
            try {
                val active = session
                val videos = if (followingOnly && active != null) api.loadFollowingFeed(active.accessToken, active.userId)
                    else api.loadFeed(active?.accessToken)
                runOnUiThread {
                    list.removeAllViews()
                    if (videos.isEmpty()) list.addView(label("No public videos are available yet.", 16f))
                    videos.forEachIndexed { index, video -> list.addView(videoCard(video, index == 0)) }
                }
            } catch (e: Exception) {
                runOnUiThread { list.removeAllViews(); list.addView(label("Could not load feed: " + (e.message ?: "Network error"), 15f)) }
            }
        }
    }

    private fun videoCard(video: NewStarVideo, autoplay: Boolean): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val player = VideoView(this)
        card.addView(player, LinearLayout.LayoutParams(-1, dp(610)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(18)); setBackgroundColor(Color.rgb(8, 10, 18)) }
        info.addView(label("@" + video.username, 16f, true))
        info.addView(label(video.caption, 14f))
        val metrics = label("♥ " + video.likes + "     ▶ " + video.views, 13f)
        info.addView(metrics)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val likeButton = button("♥ Like")
        val commentButton = button("Comment")
        val shareButton = button("Share")
        actions.addView(likeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(commentButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(shareButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        info.addView(actions)
        likeButton.setOnClickListener {
            val active = session ?: return@setOnClickListener
            likeButton.isEnabled = false
            worker.execute {
                try {
                    val liked = api.toggleLike(active.accessToken, active.userId, video.id)
                    runOnUiThread {
                        likeButton.isEnabled = true
                        likeButton.text = if (liked) "♥ Liked" else "♥ Like"
                        metrics.text = "♥ " + (video.likes + if (liked) 1 else -1).coerceAtLeast(0) + "     ▶ " + video.views
                    }
                } catch (e: Exception) {
                    runOnUiThread { likeButton.isEnabled = true; showStatus(e.message ?: "Could not update like.") }
                }
            }
        }
        commentButton.setOnClickListener { openComments(video) }
        shareButton.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, video.videoUrl) }
            startActivity(Intent.createChooser(send, "Share New Star video"))
        }
        card.addView(info)
        if (autoplay) player.post {
            player.setVideoURI(Uri.parse(video.videoUrl))
            player.setOnPreparedListener { it.isLooping = true; it.start() }
        }
        card.setOnClickListener { if (player.isPlaying) player.pause() else player.start() }
        return card
    }

    private fun openComments(video: NewStarVideo) {
        val active = session ?: return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        val entry = EditText(this).apply { hint = "Write a comment (max 500 characters)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); maxLines = 3 }
        val send = button("Post comment")
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(260)))
        panel.addView(entry, LinearLayout.LayoutParams(-1, dp(64)))
        panel.addView(send, LinearLayout.LayoutParams(-1, dp(48)))
        val dialog = AlertDialog.Builder(this).setTitle("Comments").setView(panel).setNegativeButton("Close", null).create()
        dialog.show()
        fun refreshComments() {
            list.removeAllViews()
            list.addView(label("Loading comments…", 14f))
            worker.execute {
                try {
                    val comments = api.loadComments(active.accessToken, video.id)
                    runOnUiThread {
                        list.removeAllViews()
                        if (comments.isEmpty()) list.addView(label("No comments yet. Start the conversation.", 14f))
                        comments.forEach { item ->
                            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
                            row.addView(label("@" + item.username, 13f, true))
                            row.addView(label(item.comment, 14f))
                            list.addView(row)
                        }
                    }
                } catch (e: Exception) { runOnUiThread { list.removeAllViews(); list.addView(label(e.message ?: "Could not load comments.")) } }
            }
        }
        refreshComments()
        send.setOnClickListener {
            val value = entry.text.toString().trim()
            if (value.isBlank()) { entry.error = "Write a comment first"; return@setOnClickListener }
            send.isEnabled = false
            val username = active.email.substringBefore("@").ifBlank { "creator" }
            worker.execute {
                try {
                    api.addComment(active.accessToken, active.userId, username, video.id, value)
                    runOnUiThread { entry.setText(""); send.isEnabled = true; refreshComments() }
                } catch (e: Exception) {
                    runOnUiThread { send.isEnabled = true; showStatus(e.message ?: "Could not post comment.") }
                }
            }
        }
    }

    private fun showSearch() {
        activeScreen = "Search"
        baseScreen("Search New Star")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), 0) }
        val input = EditText(this).apply { hint = "Search creator"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val search = button("Search creators")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }
        panel.addView(input, LinearLayout.LayoutParams(-1, dp(52))); panel.addView(space(10))
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(48))); panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(panel)
        search.setOnClickListener {
            val term = input.text.toString().trim()
            if (term.isBlank()) return@setOnClickListener
            results.removeAllViews(); showStatus("Searching…")
            worker.execute {
                try {
                    val found = api.searchCreators(session?.accessToken, term)
                    runOnUiThread {
                        status.visibility = View.GONE
                        if (found.isEmpty()) results.addView(label("No matching creators found."))
                        found.forEach { creator ->
                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                setBackgroundColor(Color.rgb(20, 23, 35))
                            }
                            row.addView(label("@" + creator.username, 17f, true))
                            if (creator.fullName.isNotBlank()) row.addView(label(creator.fullName, 14f))
                            if (creator.bio.isNotBlank()) row.addView(label(creator.bio, 13f))
                            row.setOnClickListener { showCreatorProfile(creator.userId) }
                            results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                        }
                    }
                } catch (e: Exception) { runOnUiThread { showStatus(e.message ?: "Search failed.") } }
            }
        }
    }

    private fun showCreatorProfile(creatorId: String) {
        baseScreen("Creator Profile")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        val heading = label("Loading creator profile…", 16f, true)
        panel.addView(heading)
        content.addView(ScrollView(this).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
        val active = session
        worker.execute {
            try {
                val creator = api.loadCreatorProfile(active?.accessToken, creatorId)
                    ?: throw IllegalStateException("Creator profile is unavailable.")
                val following = if (active != null && active.userId != creatorId) api.isFollowing(active.accessToken, active.userId, creatorId) else false
                val videos = api.loadCreatorVideos(active?.accessToken, creatorId)
                runOnUiThread {
                    panel.removeAllViews()
                    panel.addView(label("@" + creator.username, 23f, true))
                    if (creator.fullName.isNotBlank()) panel.addView(label(creator.fullName, 17f, true))
                    if (creator.bio.isNotBlank()) panel.addView(label(creator.bio, 15f))
                    if (creator.about.isNotBlank()) panel.addView(label(creator.about, 14f))
                    val followersLabel = label(creator.followerCount.toString() + " followers", 14f)
                    panel.addView(followersLabel)
                    if (active?.userId != creatorId) {
                        val follow = button(if (following) "Following" else if (creator.isPrivate) "Private account" else "Follow")
                        follow.isEnabled = !creator.isPrivate
                        panel.addView(follow, LinearLayout.LayoutParams(-1, dp(48)))
                        follow.setOnClickListener {
                            follow.isEnabled = false
                            worker.execute {
                                try {
                                    val nowFollowing = api.toggleFollow(active!!.accessToken, active.userId, creatorId)
                                    val refreshed = api.loadCreatorProfile(active.accessToken, creatorId)
                                    runOnUiThread {
                                        follow.text = if (nowFollowing) "Following" else "Follow"
                                        follow.isEnabled = true
                                        val count = refreshed?.followerCount ?: creator.followerCount
                                        followersLabel.text = count.toString() + " followers"
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { follow.isEnabled = true; showStatus(e.message ?: "Could not update follow status.") }
                                }
                            }
                        }
                    }
                    panel.addView(label(if (creator.isPrivate) "This creator has a private account." else "Videos", 18f, true))
                    if (videos.isEmpty()) panel.addView(label(if (creator.isPrivate) "Videos are visible to approved followers only." else "No public videos yet.", 14f))
                    videos.forEachIndexed { index, video -> panel.addView(videoCard(video, index == 0)) }
                }
            } catch (e: Exception) {
                runOnUiThread { panel.removeAllViews(); panel.addView(label(e.message ?: "Could not load creator profile.")) }
            }
        }
        addBottomNav("Search")
    }


    private fun promptVideoTitle(uri: Uri) {
        val titleInput = EditText(this).apply {
            hint = "Video title"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Publish video")
            .setMessage("Selected video will be uploaded to New Star.")
            .setView(titleInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upload", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val active = session
                        if (active == null) { dialog.dismiss(); showAuth(); return@setOnClickListener }
                        dialog.dismiss()
                        startNativeUpload(uri, titleInput.text.toString(), active)
                    }
                }
                dialog.show()
            }
    }

    private fun startNativeUpload(uri: Uri, title: String, active: NewStarSession) {
        val progress = ProgressDialog(this).apply {
            setTitle("Uploading to New Star")
            setMessage("Preparing video… 0%")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }
        worker.execute {
            try {
                val streamId = api.uploadVideo(this, active.accessToken, active.userId, uri, title) { percent ->
                    runOnUiThread {
                        if (!isFinishing && progress.isShowing) {
                            progress.progress = percent
                            progress.setMessage("Uploading video… " + percent + "%")
                        }
                    }
                }
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Upload submitted")
                            .setMessage("New Star received the video. Processing may take a short time. Reference: " + streamId)
                            .setPositiveButton("OK") { _, _ -> showProfile() }
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Upload failed")
                            .setMessage(e.message ?: "Could not upload video.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun showProfile() {
        activeScreen = "Profile"
        baseScreen("Your Account")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20)) }
        panel.addView(label("Signed in as\n" + (session?.email ?: "New Star creator"), 16f))
        val upload = button("Upload video")
        upload.isEnabled = isRemoteFeatureEnabled("allow_video_upload", true)
        if (!upload.isEnabled) upload.text = "Video uploads temporarily unavailable"
        val logout = button("Sign out")
        panel.addView(upload, LinearLayout.LayoutParams(-1, dp(50)))
        panel.addView(space(10))
        panel.addView(logout, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(panel)
        upload.setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        logout.setOnClickListener { prefs.edit().clear().apply(); session = null; showAuth() }
    }

    private fun addBottomNav(selected: String) {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(16, 18, 28)) }
        val tabs = buildList {
            add("Home")
            if (isRemoteFeatureEnabled("show_search", true)) add("Search")
            if (isRemoteFeatureEnabled("show_profile", true)) add("Profile")
        }
        tabs.forEach { item ->
            val tab = TextView(this).apply { text = item; gravity = Gravity.CENTER; textSize = 13f; setTextColor(if (item == selected) Color.WHITE else Color.LTGRAY) }
            nav.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f))
            tab.setOnClickListener { when (item) { "Home" -> showHome(); "Search" -> showSearch(); else -> showProfile() } }
        }
        root.addView(nav)
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(10), dp(8), dp(10))
        if (bold) setTypeface(null, 1)
    }
    private fun button(text: String) = Button(this).apply { this.text = text; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(110, 65, 220)) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showStatus(message: String) { status.text = message; status.visibility = View.VISIBLE }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
