package com.newstar.live

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingUrl = ""

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        fileCallback?.onReceiveValue(selected)
        fileCallback = null
    }

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
        container = FrameLayout(this)
        progress = ProgressBar(this).apply { isIndeterminate = true; alpha = 0f }
        web = createWebView()
        container.addView(web, FrameLayout.LayoutParams(-1, -1))
        container.addView(progress, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        setContentView(container)

        val f = IntentFilter(GamingStreamService.STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, f)
        }
        if (savedInstanceState == null) web.loadUrl(HOME_URL) else web.restoreState(savedInstanceState)
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.alpha = if (newProgress >= 100) 0f else 1f
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        filePicker.launch(intent)
                        true
                    } catch (_: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == APP_HOST) return false
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val uri = Uri.parse(url)
                    if (uri.scheme == "https" && uri.host == APP_HOST) return false
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    progress.alpha = 1f
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    progress.alpha = 0f
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showLoadError(error.description?.toString() ?: "Network error")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 500) showLoadError("Server error: ${response.statusCode}")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    showLoadError("Secure connection failed. Please check your internet connection and device date/time.")
                }
            }
            addJavascriptInterface(Bridge(), "NewStarNative")
        }
    }

    private fun showLoadError(message: String) {
        progress.alpha = 0f
        val safeMessage = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val html = """
            <html><meta name="viewport" content="width=device-width, initial-scale=1">
            <body style="margin:0;background:#101018;color:white;font:16px sans-serif;display:grid;place-items:center;min-height:100vh;text-align:center">
            <main style="padding:28px"><h2>New Star couldn't load</h2><p>$safeMessage</p>
            <button onclick="location.href='$HOME_URL'" style="padding:14px 22px;border:0;border-radius:10px;background:#7c3aed;color:white;font-size:16px">Try again</button>
            </main></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null)
    }

    private fun emit(status: String, message: String, resource: String = "") {
        val d = JSONObject().put("status", status).put("message", message).put("resourceUrl", resource)
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('newstar-native-stream-status',{detail:$d}));", null) }
    }

    inner class Bridge {
        @JavascriptInterface fun startScreenStream(url: String) {
            val u = runCatching { Uri.parse(url) }.getOrNull()
            if (u?.scheme != "https" || u.host.isNullOrBlank() || !(u.host == "cloudflarestream.com" || u.host!!.endsWith(".cloudflarestream.com"))) {
                emit("error", "Server did not provide a valid secure Cloudflare WHIP endpoint."); return
            }
            runOnUiThread {
                pendingUrl = url
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenPermission.launch(manager.createScreenCaptureIntent())
            }
        }
        @JavascriptInterface fun stopScreenStream() {
            runOnUiThread { stopService(Intent(this@MainActivity, GamingStreamService::class.java)) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        runCatching { unregisterReceiver(receiver) }
        if (::web.isInitialized) {
            web.removeJavascriptInterface("NewStarNative")
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "new-star-delta.vercel.app"
        private const val HOME_URL = "https://new-star-delta.vercel.app/"
    }
}
