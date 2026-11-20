package com.newstar.live

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var pendingUrl = ""
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingUrl.isNotBlank()) {
            val i = Intent(this, GamingStreamService::class.java).apply {
                action = GamingStreamService.START
                putExtra(GamingStreamService.RESULT_CODE, result.resultCode)
                putExtra(GamingStreamService.RESULT_DATA, result.data)
                putExtra(GamingStreamService.WHIP_URL, pendingUrl)
            }
            ContextCompat.startForegroundService(this, i)
        } else emit("error", "Android screen-sharing permission was not granted.")
        pendingUrl = ""
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            emit(i?.getStringExtra("status") ?: return, i.getStringExtra("message") ?: "", i.getStringExtra("resource") ?: "")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val u = Uri.parse(url)
                return u.scheme != "https" || u.host != "new-star-delta.vercel.app"
            }
        }
        web.addJavascriptInterface(Bridge(), "NewStarNative")
        val f = IntentFilter(GamingStreamService.STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, f)
        }
        web.loadUrl("https://new-star-delta.vercel.app/gaming.html")
    }
    private fun emit(status: String, message: String, resource: String = "") {
        val d = JSONObject().put("status", status).put("message", message).put("resourceUrl", resource)
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('newstar-native-stream-status',{detail:$d}));", null) }
    }
    inner class Bridge {
        @JavascriptInterface fun startScreenStream(url: String) {
            val u = runCatching { Uri.parse(url) }.getOrNull()
            if (u?.scheme != "https" || u.host.isNullOrBlank() || !u.host!!.endsWith("cloudflarestream.com")) {
                emit("error", "Server did not provide a valid secure Cloudflare WHIP endpoint."); return
            }
            runOnUiThread {
                pendingUrl = url
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenPermission.launch(manager.createScreenCaptureIntent())
            }
        }
        @JavascriptInterface fun stopScreenStream() { runOnUiThread { stopService(Intent(this@MainActivity, GamingStreamService::class.java)) } }
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        web.removeJavascriptInterface("NewStarNative"); web.destroy(); super.onDestroy()
    }
}
