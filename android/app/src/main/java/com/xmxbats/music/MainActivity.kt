package com.xmxbats.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        // GANTI ini ke domain hasil deploy Netlify lu (pakai https, tanpa trailing slash)
        const val BASE_URL = "https://backendxm.netlify.app"
    }

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)
        configureWebView()

        // Saat notifikasi/lockscreen dipencet -> teruskan ke JS (TP/NX/PV/seek)
        MediaBridge.onNativeAction = { action, seek ->
            runOnUiThread { sendActionToWeb(action, seek) }
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL)
        }
    }

    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Use normal WebView HTTP cache for the UI, but never let the media
        // layer depend on a cached HTML/audio response.
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.userAgentString = s.userAgentString + " XMXbatsMusicApp/1.1"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.keepScreenOn = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith(BASE_URL)) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) { }
                    true
                }
            }
        }
    }

    private fun sendActionToWeb(action: String, seek: Double?) {
        val js = when (action) {
            "toggle" -> "if(typeof TP==='function'){TP();}"
            "next" -> "if(typeof NX==='function'){NX();}"
            "prev" -> "if(typeof PV==='function'){PV(true);}"
            "seek" -> "if(typeof AU!=='undefined'&&AU){AU.currentTime=${seek ?: 0};}"
            else -> null
        }
        js?.let { webView.evaluateJavascript(it, null) }
    }

    /** Dipanggil dari JS: window.AndroidBridge.updateMeta(...) / updateState(...) */
    inner class JsBridge {

        @JavascriptInterface
        fun updateMeta(title: String, artist: String, artwork: String, duration: Double) {
            MediaBridge.title = title.ifBlank { "XM Xbats Music" }
            MediaBridge.artist = artist
            if (artwork.isNotBlank() && artwork != MediaBridge.artwork) {
                MediaBridge.artwork = artwork
                MediaNotificationService.resetArtwork()
            }
            MediaBridge.duration = duration
            pushToService()
        }

        @JavascriptInterface
        fun updateState(isPlaying: Boolean, position: Double, duration: Double) {
            MediaBridge.isPlaying = isPlaying
            MediaBridge.position = position
            if (duration > 0) MediaBridge.duration = duration
            pushToService()
        }

        @JavascriptInterface
        fun stop() {
            val intent = Intent(this@MainActivity, MediaNotificationService::class.java)
            intent.action = MediaNotificationService.ACTION_STOP
            startService(intent)
        }

        private fun pushToService() {
            val intent = Intent(this@MainActivity, MediaNotificationService::class.java)
            intent.action = MediaNotificationService.ACTION_UPDATE
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Wake the Web Audio graph after returning from background/lockscreen.
        webView.evaluateJavascript(
            "if(typeof resumeAudioForAndroid==='function'){resumeAudioForAndroid();}",
            null
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
