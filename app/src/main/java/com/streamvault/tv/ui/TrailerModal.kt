package com.streamvault.tv.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Keep the WebView/video surface in a native, opaque, accelerated window. A clipped Compose
 * graphics layer around AndroidView can prevent Chromium's video plane from appearing on TVs.
 * Composition owns the dialog's lifetime; dismissal leaves the underlying title page untouched.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerModal(videoId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val close by rememberUpdatedState(onClose)
    DisposableEffect(context, videoId) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val identity = "https://${context.packageName}/"
        val valid = videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBackgroundDrawable(ColorDrawable(Color.rgb(9, 14, 19)))
            setFormat(PixelFormat.OPAQUE)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(Color.rgb(9, 14, 19))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply { text = "Trailer"; textSize = 22f; gravity = Gravity.CENTER_VERTICAL; setTextColor(Color.WHITE) }
        val closeButton = Button(context).apply {
            text = "Close trailer"; isAllCaps = false; textSize = 14f
            backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.WHITE, Color.rgb(39, 48, 55)))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
            setOnClickListener { close() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        val videoHost = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(videoHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val errorLabel = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 14f; visibility = View.GONE }
        root.addView(errorLabel, LinearLayout.LayoutParams(-1, -2))
        fun showError(message: String) { errorLabel.text = message; errorLabel.visibility = View.VISIBLE }
        var webView: WebView? = null
        var customView: View? = null
        var customCallback: WebChromeClient.CustomViewCallback? = null
        var disposed = false
        var ready = false
        fun hideCustomView() {
            customView?.let { videoHost.removeView(it) }
            customView = null
            val callback = customCallback
            customCallback = null
            callback?.onCustomViewHidden()
            webView?.visibility = View.VISIBLE
        }
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable { if (!disposed && !ready && errorLabel.visibility != View.VISIBLE) showError("YouTube did not load. Check the connection and try again.") }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (!disposed) when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.evaluateJavascript("if(window.player){window.player.pauseVideo();}", null)
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        dialog.setContentView(root)
        dialog.setOnCancelListener { close() }
        dialog.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && customView != null) {
                hideCustomView(); true
            } else false
        }
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.94f).toInt())
        closeButton.requestFocus()
        if (!valid) showError("A direct YouTube trailer is not available for this title.")
        else runCatching {
            WebView(context).apply {
                webView = this
                // Inherit the accelerated native window; do not flatten the video into an extra layer.
                setLayerType(View.LAYER_TYPE_NONE, null)
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.mediaPlaybackRequiresUserGesture = false
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        request.isForMainFrame || request.url.scheme != "https"
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        if (disposed || customView != null) { callback.onCustomViewHidden(); return }
                        (view.parent as? ViewGroup)?.removeView(view)
                        customView = view; customCallback = callback
                        visibility = View.GONE
                        videoHost.addView(view, FrameLayout.LayoutParams(-1, -1))
                        view.requestFocus()
                    }
                    override fun onHideCustomView() = hideCustomView()
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (!disposed) when {
                            message.message() == "STREAMVAULT_TRAILER_READY" -> { ready = true; handler.removeCallbacks(timeout) }
                            message.message().startsWith("STREAMVAULT_TRAILER_ERROR:") ->
                                showError("This trailer cannot be played here. It may be unavailable or block embedded playback.")
                        }
                        return true
                    }
                }
                videoHost.addView(this, FrameLayout.LayoutParams(-1, -1))
                val embed = Uri.parse("https://www.youtube.com/embed/$videoId").buildUpon()
                    .appendQueryParameter("enablejsapi", "1").appendQueryParameter("autoplay", "1")
                    .appendQueryParameter("playsinline", "1").appendQueryParameter("fs", "1")
                    .appendQueryParameter("origin", identity.trimEnd('/')).build().toString()
                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body{margin:0;width:100vw;height:100vh;background:#000;overflow:hidden}#player{position:absolute;inset:0;display:block;width:100vw;height:100vh;border:0}</style></head><body><iframe id="player" src="$embed" allow="autoplay;encrypted-media;fullscreen" allowfullscreen></iframe><script src="https://www.youtube.com/iframe_api"></script><script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{events:{onReady:function(e){console.log('STREAMVAULT_TRAILER_READY');e.target.playVideo();},onError:function(e){console.log('STREAMVAULT_TRAILER_ERROR:'+e.data);}}});}</script></body></html>"""
                doOnLayout {
                    if (!disposed) {
                        Log.i("TrailerPlayer", "Native video host ${width}x${height}, accelerated=$isHardwareAccelerated")
                        loadDataWithBaseURL(identity, html, "text/html", "UTF-8", null)
                    }
                }
                handler.postDelayed(timeout, 20_000)
            }
        }.onFailure { showError("This TV could not open the embedded trailer player.") }
        onDispose {
            disposed = true
            lifecycle?.removeObserver(observer)
            handler.removeCallbacks(timeout)
            runCatching { hideCustomView() }
            webView?.let { view -> runCatching {
                view.stopLoading(); view.loadUrl("about:blank"); view.onPause()
                (view.parent as? ViewGroup)?.removeView(view)
                view.removeAllViews(); view.destroy()
            } }
            webView = null
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
    }
}
