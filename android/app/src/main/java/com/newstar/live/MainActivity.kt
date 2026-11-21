package com.newstar.live

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.*
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingUrl = ""
    private var pendingMicEnabled = false
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingDownload: DownloadManager.Request? = null

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

    private val webMediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request == null) return@registerForActivityResult
        val resources = request.resources
        val supported = resources.isNotEmpty() && resources.all {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        val allGranted = supported && resources.all { resource ->
            val permission = when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> return@all false
            }
            grants[permission] == true ||
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) request.grant(resources) else request.deny()
    }

    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingDownload
        pendingDownload = null
        if (granted && request != null) enqueueDownloadRequest(request)
        else Toast.makeText(this, "Downloads में सेव करने की अनुमति नहीं मिली।", Toast.LENGTH_LONG).show()
    }

    private val gamingMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicEnabled = granted
        launchGamingScreenPermission()
    }

    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingUrl.isNotBlank()) {
            val i = Intent(this, GamingStreamService::class.java).apply {
                action = GamingStreamService.START
                putExtra(GamingStreamService.RESULT_CODE, result.resultCode)
                putExtra(GamingStreamService.RESULT_DATA, result.data)
                putExtra(GamingStreamService.WHIP_URL, pendingUrl)
                putExtra(GamingStreamService.INCLUDE_MIC, pendingMicEnabled)
            }
            ContextCompat.startForegroundService(this, i)
        } else {
            emit("error", "Android screen-sharing permission was not granted.")
        }
        pendingUrl = ""
        pendingMicEnabled = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            emit(i?.getStringExtra("status") ?: return, i.getStringExtra("message") ?: "", i.getStringExtra("resource") ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        web = createWebView()
        container.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(container)

        val f = IntentFilter(GamingStreamService.STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, f)
        }
        if (savedInstanceState == null) web.loadUrl(HOME_URL) else web.restoreState(savedInstanceState)
    }

    private fun launchGamingScreenPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenPermission.launch(manager.createScreenCaptureIntent())
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
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val trustedOrigin = request.origin.scheme == "https" && request.origin.host == APP_HOST
                        val supportedResources = request.resources.isNotEmpty() && request.resources.all {
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        }
                        if (!trustedOrigin || !supportedResources) {
                            request.deny()
                            return@runOnUiThread
                        }
                        val requiredPermissions = buildList {
                            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add(Manifest.permission.RECORD_AUDIO)
                            if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add(Manifest.permission.CAMERA)
                        }
                        val missingPermissions = requiredPermissions.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missingPermissions.isEmpty()) {
                            request.grant(request.resources)
                        } else {
                            pendingWebPermission?.deny()
                            pendingWebPermission = request
                            webMediaPermissions.launch(missingPermissions.toTypedArray())
                        }
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingWebPermission == request) pendingWebPermission = null
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
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
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showLoadError(error.errorCode, error.description?.toString() ?: "Network error")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 500) showLoadError(response.statusCode, "HTTP ${response.statusCode}")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    showLoadError(error.primaryError, "TLS/SSL certificate validation failed")
                }
            }
            addJavascriptInterface(Bridge(), "NewStarNative")
        }
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "सुरक्षा कारण से यह डाउनलोड लिंक स्वीकार नहीं किया गया।", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(150)
                .ifBlank { "new-star-download" }
            val request = DownloadManager.Request(uri).apply {
                setTitle(filename)
                setDescription("New Star से डाउनलोड हो रहा है")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = request
                legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueueDownloadRequest(request)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "डाउनलोड शुरू नहीं हो सका। लिंक या इंटरनेट जाँचें।", Toast.LENGTH_LONG).show()
        }
    }

    private fun enqueueDownloadRequest(request: DownloadManager.Request) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "डाउनलोड शुरू हुआ। Notification में प्रगति देखें।", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "डाउनलोड शुरू नहीं हो सका। लिंक या इंटरनेट जाँचें।", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLoadError(errorCode: Int, detail: String) {
        fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        val connectivity = runCatching {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            when {
                capabilities == null -> "No active network detected"
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "Internet connection available"
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "Network connected; internet not verified"
                else -> "Connected network has no internet capability"
            }
        }.getOrDefault("Network status unavailable")
        val webViewVersion = runCatching { WebView.getCurrentWebViewPackage()?.versionName ?: "Unavailable" }
            .getOrDefault("Unavailable")
        val diagnostics = """
            Android: ${escapeHtml(Build.VERSION.RELEASE)} (API ${Build.VERSION.SDK_INT})
            WebView: ${escapeHtml(webViewVersion)}
            Network: ${escapeHtml(connectivity)}
            Error code: ${escapeHtml(errorCode.toString())}
            Technical detail: ${escapeHtml(detail.take(240))}
            App URL: ${escapeHtml(HOME_URL)}
        """.trimIndent()
        val html = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="color-scheme" content="dark">
            <style>
              *{box-sizing:border-box}body{margin:0;background:#101018;color:#fff;font:16px system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;padding:20px}
              main{width:min(100%,480px);padding:26px;border:1px solid #343444;border-radius:20px;background:#1b1b27}
              h2{margin:0 0 10px;font-size:24px}p{color:#c9c9d5;line-height:1.55}
              button{width:100%;padding:14px;border:0;border-radius:12px;background:#fe2c55;color:#fff;font-size:16px;font-weight:700;margin:8px 0;cursor:pointer}
              button.secondary{background:#343444}details{margin-top:14px;color:#ddd}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;color:#bdbdcc;background:#11111a;padding:12px;border-radius:10px}
              .hint{font-size:13px;color:#9f9faf}
            </style></head><body><main>
              <h2>New Star अभी नहीं खुल पाया</h2>
              <p>कनेक्शन या सर्वर में अस्थायी समस्या हो सकती है। इंटरनेट जाँचें और फिर कोशिश करें। आपकी ऐप सुरक्षा सेटिंग्स बदलने की जरूरत नहीं है।</p>
              <button onclick="location.href='$HOME_URL'">फिर से कोशिश करें</button>
              <button class="secondary" onclick="location.reload()">इस पेज को रीफ़्रेश करें</button>
              <details><summary>तकनीकी जाँच (Diagnostics)</summary><pre>$diagnostics</pre></details>
              <p class="hint">अगर समस्या बनी रहे, Diagnostics में दिख रही जानकारी सहायता टीम को दें। पासवर्ड या OTP साझा न करें।</p>
            </main></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null)
    }

    private fun emit(status: String, message: String, resource: String = "") {
        val d = JSONObject().put("status", status).put("message", message).put("resourceUrl", resource)
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('newstar-native-stream-status',{detail:$d}));", null) }
    }

    inner class Bridge {
        @JavascriptInterface fun openAndroidSettings(section: String) {
            val intent = when (section) {
                "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                else -> null
            } ?: run {
                emit("error", "यह Android settings विकल्प उपलब्ध नहीं है।")
                return
            }
            runOnUiThread {
                try {
                    startActivity(intent)
                    emit("settings-opened", "Android की संबंधित Settings खुल गई हैं।")
                } catch (_: Exception) {
                    if (section == "notifications") {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            })
                            emit("settings-opened", "App info खुल गया है। Notifications वहाँ से खोलें।")
                        } catch (_: Exception) {
                            emit("error", "Android App Settings नहीं खुल सकीं।")
                        }
                    } else {
                        emit("error", "Android App Settings नहीं खुल सकीं।")
                    }
                }
            }
        }

        @JavascriptInterface fun startScreenStream(url: String) {
            val u = runCatching { Uri.parse(url) }.getOrNull()
            if (u?.scheme != "https" || u.host.isNullOrBlank() || !(u.host == "cloudflarestream.com" || u.host!!.endsWith(".cloudflarestream.com"))) {
                emit("error", "Server did not provide a valid secure Cloudflare WHIP endpoint."); return
            }
            runOnUiThread {
                pendingUrl = url
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    pendingMicEnabled = true
                    launchGamingScreenPermission()
                } else {
                    // Screen sharing can still proceed if the creator declines microphone access.
                    pendingMicEnabled = false
                    gamingMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        @JavascriptInterface fun setMicrophoneEnabled(enabled: Boolean) {
            val intent = Intent(this@MainActivity, GamingStreamService::class.java).apply {
                action = GamingStreamService.SET_MIC
                putExtra(GamingStreamService.MIC_ENABLED, enabled)
            }
            runCatching { startService(intent) }.onFailure {
                emit("error", "Could not change the Gaming LIVE microphone state.")
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
        pendingWebPermission?.let { runCatching { it.deny() } }
        pendingWebPermission = null
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
