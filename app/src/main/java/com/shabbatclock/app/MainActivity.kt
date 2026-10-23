package com.shabbatclock.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        fileCb?.onReceiveValue(uris)
        fileCb = null
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash recorder: if the app ever crashes, the reason is shown as a message on the next launch.
        val cp = getSharedPreferences("crash", Context.MODE_PRIVATE)
        cp.getString("last", null)?.let {
            cp.edit().remove("last").apply()
            android.widget.Toast.makeText(this, "תקלה קודמת: $it", android.widget.Toast.LENGTH_LONG).show()
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val top = e.stackTrace.firstOrNull { it.className.startsWith("com.shabbatclock") }
                val msg = e.javaClass.simpleName + ": " + (e.message ?: "").take(120) +
                    (if (top != null) " @" + top.fileName + ":" + top.lineNumber else "")
                cp.edit().putString("last", msg).commit()
            } catch (_: Throwable) {}
            prevHandler?.uncaughtException(t, e)
        }

        // When woken by an alarm, show over the lock screen and turn the screen on.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val shimJs = assets.open("native-shim.js").bufferedReader().use { it.readText() }

        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(applicationContext), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // the page's renderer died (e.g. out of memory): reload instead of taking the whole app down
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { view.loadUrl("https://appassets.androidplatform.net/assets/index.html") } catch (_: Throwable) {}
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(shimJs, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { pickFile.launch(params.createIntent()); true }
                catch (e: Exception) { fileCb = null; false }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        askPerms.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        web.resumeTimers()
        backgroundSetup()
    }

    override fun onStop() {
        super.onStop()
        web.resumeTimers() // keep page timers running while the screen is off (best effort)
    }

    /** One-time system screens: battery exemption, then full-screen permission (Android 14+). */
    private fun backgroundSetup() {
        val p = getSharedPreferences("setup", Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!p.getBoolean("battery", false)) {
            p.edit().putBoolean("battery", true).apply()
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                    return
                } catch (_: Exception) {}
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !p.getBoolean("fullscreen", false)) {
            p.edit().putBoolean("fullscreen", true).apply()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
