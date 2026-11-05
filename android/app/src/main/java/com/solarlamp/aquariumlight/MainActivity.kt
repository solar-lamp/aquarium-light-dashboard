package com.solarlamp.aquariumlight

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Shows the same dashboard as the website. The page files are bundled in the
 * APK; only config.js is generated here, from the broker details in Settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var settings: AppSettings
    private lateinit var assetLoader: WebViewAssetLoader

    // Broker details the page was last loaded with.
    private var loadedFingerprint: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.webChromeClient = WebChromeClient()   // enables confirm() dialogs
        webView.webViewClient = DashboardClient()

        loadDashboard()

        if (!settings.hasCredentials) {
            openSettings()
        }

        MonitorService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()

        // Pick up broker changes made in Settings.
        if (settings.connectionFingerprint != loadedFingerprint) {
            loadDashboard()
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            openSettings()
            true
        }
        R.id.action_reload -> {
            loadDashboard()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun loadDashboard() {
        loadedFingerprint = settings.connectionFingerprint
        webView.loadUrl(START_URL)
    }

    /** Same shape as the website's config.js, filled from Settings. */
    private fun configScript(): WebResourceResponse {
        val mqtt = JSONObject()
            .put("host", settings.host)
            .put("port", settings.wsPort)
            .put("protocol", "wss")
            .put("path", "/mqtt")
            .put("username", settings.username)
            .put("password", settings.password)

        val js = "window.AQUARIUM_CONFIG = ${JSONObject().put("mqtt", mqtt)};"

        return WebResourceResponse("text/javascript", "utf-8",
            ByteArrayInputStream(js.toByteArray(Charsets.UTF_8)))
    }

    private inner class DashboardClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url

            if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN && url.path == CONFIG_PATH) {
                return configScript()
            }

            // Bundled files; anything else (CDN scripts, fonts) loads normally.
            return assetLoader.shouldInterceptRequest(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                return false
            }

            // External links open in the browser, not inside the app.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
            } catch (e: ActivityNotFoundException) {
                // No browser installed; ignore.
            }
            return true
        }
    }

    companion object {
        private const val START_URL =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/web/index.html"
        private const val CONFIG_PATH = "/assets/web/config.js"
    }
}
