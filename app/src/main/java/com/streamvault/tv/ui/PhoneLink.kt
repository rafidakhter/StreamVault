package com.streamvault.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.tv.data.Repo
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Typing long playlist URLs with a remote is miserable, so the TV runs a tiny
 * web page on the local network. Scan the QR code with your phone, paste the
 * link there, done. Only reachable on your own Wi-Fi while this screen is open.
 */
class PhoneLinkServer(private val onSubmit: (Map<String, String>) -> String) {
    @Volatile private var socket: ServerSocket? = null
    var port: Int = 0
        private set

    fun start(): Boolean {
        for (p in intArrayOf(8765, 8766, 8767, 0)) {
            try {
                val s = ServerSocket(p)
                socket = s
                port = s.localPort
                break
            } catch (e: IOException) {
            }
        }
        val server = socket ?: return false
        thread(name = "phone-link", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
        return true
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            var length = 0
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (length > 0) {
                val buf = CharArray(length.coerceAtMost(64_000))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""
            val html = if (method == "POST") page(onSubmit(parseForm(body))) else page(null)
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun page(message: String?): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>StreamVault</title><style>
body{font-family:system-ui,-apple-system,sans-serif;background:#09090f;color:#fff;margin:0;padding:24px}
.card{max-width:520px;margin:auto;background:#15151f;border-radius:18px;padding:24px}
h1{margin:0 0 4px;font-size:24px}p{color:#a3a3b8}label{display:block;margin:16px 0 6px;font-weight:600}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:10px;border:1px solid #333;background:#0f0f17;color:#fff;font-size:16px}
button{margin-top:22px;width:100%;padding:15px;border:0;border-radius:999px;background:#8b5cf6;color:#fff;font-size:17px;font-weight:700}
.msg{background:#22222f;border-left:4px solid #8b5cf6;padding:12px;border-radius:8px;margin-bottom:12px}
</style></head><body><div class="card">
<h1>StreamVault</h1><p>Send a playlist or your subtitle key to your TV.</p>
${if (message != null) "<div class=\"msg\">${escape(message)}</div>" else ""}
<form method="post">
<label>Playlist name</label><input name="name" placeholder="My IPTV">
<label>M3U playlist link</label><input name="url" type="url" placeholder="http://provider.com/get.php?...">
<label>OpenSubtitles API key (optional)</label><input name="apikey" placeholder="leave blank to keep current">
<button type="submit">Send to TV</button></form></div></body></html>
""".trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

fun localIpAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
} catch (e: Exception) {
    null
}

fun qrBitmap(text: String, size: Int = 360): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp.asImageBitmap()
}

/** QR code + address. Starts the local server while it is on screen. */
@Composable
fun PhoneLinkCard(modifier: Modifier = Modifier) {
    var address by remember { mutableStateOf<String?>(null) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val server = PhoneLinkServer { form ->
            val url = form["url"].orEmpty().trim()
            val name = form["name"].orEmpty().trim()
            val key = form["apikey"].orEmpty().trim()
            val messages = ArrayList<String>()
            if (key.isNotEmpty()) {
                Repo.updateSettings { it.copy(osApiKey = key) }
                messages.add("Subtitle API key saved.")
            }
            if (url.isNotEmpty()) {
                Repo.addPlaylistAsync(name, url) { lastMessage = it }
                messages.add("Playlist sent! Your TV is loading it now.")
            }
            val msg = if (messages.isEmpty()) "Nothing to send: fill in at least one field." else messages.joinToString(" ")
            lastMessage = msg
            msg
        }
        val ip = localIpAddress()
        address = if (server.start() && ip != null) "http://$ip:${server.port}" else null
        onDispose { server.stop() }
    }
    val qr = remember(address) { address?.let { runCatching { qrBitmap(it) }.getOrNull() } }

    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(SV.Surface).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add from your phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Scan with your phone camera (same Wi-Fi)", color = SV.Dim, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        if (qr != null) {
            Image(qr, contentDescription = "QR code", modifier = Modifier.size(190.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.height(10.dp))
            Text(address.orEmpty(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Connect the TV to Wi-Fi or Ethernet to use this.", color = SV.Dim)
        }
        if (lastMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(lastMessage.orEmpty(), color = SV.Accent, fontSize = 14.sp)
        }
    }
}
