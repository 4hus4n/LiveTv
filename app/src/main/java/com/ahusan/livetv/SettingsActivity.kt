package com.ahusan.livetv

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

private const val PHONE_PORT = 8090

class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var urlInput: EditText
    private var server: PhoneLinkServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        urlInput = findViewById(R.id.urlInput)
        urlInput.setText(prefs.playlistUrl)
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(urlInput.text.toString()); true } else false
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save(urlInput.text.toString()) }
        findViewById<View>(R.id.btnDefault).setOnClickListener { save(DEFAULT_PLAYLIST) }

        val previewBtn = findViewById<TextView>(R.id.btnPreview)
        fun showPreviewState() {
            previewBtn.text = if (prefs.livePreview) "On" else "Off"
        }
        showPreviewState()
        previewBtn.setOnClickListener {
            prefs.livePreview = !prefs.livePreview
            showPreviewState()
        }

        findViewById<View>(R.id.btnSave).requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val qr = findViewById<ImageView>(R.id.qr)
        val link = findViewById<TextView>(R.id.link)
        val ip = localIp()
        if (ip == null) {
            link.text = "Connect the TV to Wi-Fi to send a link from your phone"
            return
        }
        val address = "http://$ip:$PHONE_PORT/"
        server = PhoneLinkServer(PHONE_PORT, { prefs.playlistUrl }) { url ->
            runOnUiThread { save(url) }
        }.also { it.start() }
        qr.setImageBitmap(makeQr(address, 440))
        link.text = "Scan, or open on a phone on the same Wi-Fi:\n$address"
    }

    override fun onStop() {
        super.onStop()
        server?.stop()
        server = null
    }

    private fun save(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            Toast.makeText(this, "Enter a link starting with http:// or https://", Toast.LENGTH_LONG).show()
            return
        }
        prefs.playlistUrl = url
        prefs.lastGroup = null
        Toast.makeText(this, "Playlist saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}

/** Tiny web page the phone opens (via QR) to send a playlist URL to the TV. */
class PhoneLinkServer(
    private val port: Int,
    private val currentUrl: () -> String,
    private val onUrl: (String) -> Unit,
) {
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        thread(isDaemon = true, name = "phone-link") {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                socket = s
                while (!s.isClosed) {
                    val client = s.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (_: IOException) {
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val params = path.substringAfter('?', "").split('&').mapNotNull {
                    val p = it.split('=', limit = 2)
                    if (p.size == 2) p[0] to decode(p[1]) else null
                }.toMap()
                val newUrl = params["url"]?.trim()

                val body = if (path.startsWith("/set") && !newUrl.isNullOrEmpty()) {
                    onUrl(newUrl)
                    page("<h2>Sent to TV ✓</h2><p>The TV is loading:</p><p class='u'>${esc(newUrl)}</p>" +
                        "<p><a href='/'>Send another</a></p>")
                } else {
                    page("<h2>Live TV playlist</h2>" +
                        "<form action='/set' method='get'>" +
                        "<input name='url' type='url' value='${esc(currentUrl())}' placeholder='https://…/playlist.m3u' autofocus>" +
                        "<button>Send to TV</button></form>")
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = c.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun page(content: String) = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Live TV</title>
        <style>
        body{font-family:system-ui,sans-serif;background:#0E1116;color:#E6EDF3;margin:0;padding:24px}
        h2{margin-top:0}
        input{width:100%;box-sizing:border-box;padding:14px;font-size:16px;border-radius:10px;border:1px solid #333;background:#1B2129;color:#E6EDF3}
        button{margin-top:14px;width:100%;padding:14px;font-size:17px;border:0;border-radius:10px;background:#3FA9F5;color:#000;font-weight:600}
        .u{word-break:break-all;color:#8B949E}
        a{color:#3FA9F5}
        </style></head><body>$content</body></html>
    """.trimIndent()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;")

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
}
