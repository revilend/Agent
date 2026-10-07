package com.revilend.ai.assistant.ui

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen WebView host for AI generated mini apps. Used as the reliable
 * fallback when an external browser cannot open the generated file.
 */
class WebAppActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("title") ?: "Revilend Web App"
        val html = intent.getStringExtra("html")
        val path = intent.getStringExtra("path")

        try {
            title.let { this.title = it }
        } catch (e: Exception) {
            // ignore
        }

        val view = WebView(this)
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadWithOverviewMode = true
            view.settings.useWideViewPort = true
            view.webViewClient = WebViewClient()
        } catch (e: Exception) {
            // keep default settings
        }

        if (!html.isNullOrEmpty()) {
            view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        } else if (!path.isNullOrEmpty()) {
            view.loadUrl("file://$path")
        } else {
            view.loadData("<html><body style='background:#070b12;color:#e6edf3'>Bo'sh sahifa</body></html>", "text/html", "UTF-8")
        }

        webView = view
        setContentView(view)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        webView = null
    }
}
