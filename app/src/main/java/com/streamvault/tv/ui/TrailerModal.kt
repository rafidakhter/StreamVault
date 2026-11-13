package com.streamvault.tv.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Uses YouTube's player, not extracted media URLs. Closing releases the WebView and its decoder. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerModal(videoId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var webView by remember(videoId) { mutableStateOf<WebView?>(null) }
    var ready by remember(videoId) { mutableStateOf(false) }
    val valid = videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
    var error by remember(videoId) { mutableStateOf<String?>(if (valid) null else "A direct YouTube trailer is not available for this title.") }
    val identity = "https://${context.packageName}/"
    LaunchedEffect(videoId) {
        delay(20_000)
        if (!ready && error == null) error = "YouTube did not load. Check the connection and try again."
    }
    DisposableEffect(videoId) {
        onDispose {
            webView?.let { view -> runCatching { view.stopLoading(); view.loadUrl("about:blank"); view.onPause(); view.removeAllViews(); view.destroy() } }
            webView = null
        }
    }
    val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle, videoId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                webView?.evaluateJavascript("if(window.player){window.player.pauseVideo();}", null)
                webView?.onPause()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) webView?.onResume()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).clip(RoundedCornerShape(20.dp)).background(SV.Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Trailer", color = Color.White)
                TvButton("Close trailer", small = true, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                if (valid) AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                    runCatching {
                        WebView(viewContext).apply {
                            webView = this
                            setBackgroundColor(AndroidColor.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame || request.url.scheme != "https"
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    when {
                                        message.message() == "STREAMVAULT_TRAILER_READY" -> { ready = true; requestFocus() }
                                        message.message().startsWith("STREAMVAULT_TRAILER_ERROR:") -> error = "This trailer cannot be played here. It may be unavailable or block embedded playback."
                                    }
                                    return true
                                }
                            }
                            val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head><body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'$videoId',playerVars:{autoplay:1,playsinline:1,fs:0,origin:'$identity'},events:{onReady:function(e){console.log('STREAMVAULT_TRAILER_READY');e.target.playVideo();},onError:function(e){console.log('STREAMVAULT_TRAILER_ERROR:'+e.data);}}});}</script></body></html>"""
                            loadDataWithBaseURL(identity, html, "text/html", "UTF-8", null)
                        }
                    }.getOrElse {
                        error = "This TV could not open the embedded trailer player."
                        android.widget.FrameLayout(viewContext)
                    }
                })
            }
            error?.let { Text(it, color = SV.Dim) }
        }
    }
}
