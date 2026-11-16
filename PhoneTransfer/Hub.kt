package com.ftlink.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

/** type = "chat" | "file";  state = "ok" | "sending" | "downloading" | "failed" */
data class Msg(
    val id: String,
    val mine: Boolean,
    val type: String,
    val text: String = "",
    val name: String = "",
    val size: Long = 0,
    val path: String = "",
    val ts: Long = System.currentTimeMillis(),
    val state: String = "ok",
    val progress: Float = 0f,
)

private fun Msg.toJson() = JSONObject().put("id", id).put("mine", mine).put("type", type)
    .put("text", text).put("name", name).put("size", size).put("path", path)
    .put("ts", ts).put("state", state)

private fun JSONObject.toMsg() = Msg(
    getString("id"), getBoolean("mine"), getString("type"), optString("text"),
    optString("name"), optLong("size"), optString("path"), optLong("ts"), optString("state", "ok")
)

object Hub {
    private lateinit var app: Context
    private var inited = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val status = MutableStateFlow("Starting…")

    @Volatile private var ws: WebSocket? = null
    private var job: Job? = null

    // ---------- settings
    private val prefs get() = app.getSharedPreferences("ftlink", Context.MODE_PRIVATE)
    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v.trim()).apply()
    var port: Int
        get() = prefs.getInt("port", 8765)
        set(v) = prefs.edit().putInt("port", v).apply()
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    val receivedDir: File get() = File(app.getExternalFilesDir(null), "Received").also { it.mkdirs() }
    val sentDir: File get() = File(app.getExternalFilesDir(null), "Sent").also { it.mkdirs() }
    private val histFile get() = File(app.filesDir, "history.json")

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        inited = true
        try {
            val arr = JSONArray(histFile.readText())
            messages.value = (0 until arr.length()).map {
                val m = arr.getJSONObject(it).toMsg()
                if (m.state == "sending" || m.state == "downloading") m.copy(state = "failed") else m
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            messages.value.takeLast(2000).forEach { arr.put(it.toJson()) }
            histFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun add(m: Msg) { messages.update { it + m }; save() }
    private fun upd(id: String, persist: Boolean = false, f: (Msg) -> Msg) {
        messages.update { l -> l.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    // ---------- connection loop (auto-reconnect forever)
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun restart() {
        job?.cancel(); ws?.cancel(); job = null
        start()
    }

    private fun httpUrl(h: String, vararg seg: String): HttpUrl.Builder {
        val b = HttpUrl.Builder().scheme("http").host(h).port(port)
        seg.forEach { b.addPathSegment(it) }
        return b.addQueryParameter("token", token)
    }

    private suspend fun runLoop() {
        while (true) {
            val h = host
            if (h.isBlank()) {
                status.value = "Open Settings and enter your PC address"
                delay(2000); continue
            }
            status.value = "Connecting to $h…"
            val closed = CompletableDeferred<String?>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket; status.value = "Connected to $h"
                }
                override fun onMessage(webSocket: WebSocket, text: String) = handleIncoming(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(if (response?.code == 401) "Wrong token" else "Can't reach PC")
                }
            }
            val socket = try {
                client.newWebSocket(Request.Builder().url(httpUrl(h, "ws").build()).build(), listener)
            } catch (e: IllegalArgumentException) {
                status.value = "Invalid address"; delay(3000); continue
            }
            val err = try { closed.await() } finally { ws = null; socket.cancel() }
            status.value = (err ?: "Disconnected") + " – retrying"
            delay(3000)
        }
    }

    // ---------- incoming (PC -> phone)
    private fun handleIncoming(text: String) {
        val j = try { JSONObject(text) } catch (_: Exception) { return }
        val id = j.optString("id")
        if (id.isEmpty()) return
        val existing = messages.value.find { it.id == id }
        val ts = (j.optDouble("ts", System.currentTimeMillis() / 1000.0) * 1000).toLong()
        when (j.optString("type")) {
            "chat" -> if (existing == null) add(Msg(id, false, "chat", text = j.optString("text"), ts = ts))
            "file" -> if (existing == null) {
                val m = Msg(id, false, "file", name = j.optString("name"), size = j.optLong("size"),
                    ts = ts, state = "downloading")
                add(m); download(m)
            } else if (existing.state == "failed" && !existing.mine) {
                upd(id) { it.copy(state = "downloading", progress = 0f) }
                download(existing)
            }
        }
    }

    private fun download(m: Msg) {
        scope.launch {
            val dest = unique(File(receivedDir, safeName(m.name)))
            try {
                val url = httpUrl(host, "files", m.id).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: m.size.coerceAtLeast(1)
                    var done = 0L
                    var last = -1
                    body.byteStream().use { ins ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; upd(m.id) { it.copy(progress = p / 100f) } }
                            }
                        }
                    }
                }
                upd(m.id, true) { it.copy(state = "ok", progress = 1f, path = dest.absolutePath, size = dest.length()) }
            } catch (e: Exception) {
                dest.delete()
                upd(m.id, true) { it.copy(state = "failed") }
            }
        }
    }

    // ---------- outgoing (phone -> PC)
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val m = Msg(UUID.randomUUID().toString(), true, "chat", text = t)
        val ok = ws?.send(JSONObject().put("type", "chat").put("id", m.id).put("text", t).toString()) ?: false
        add(m.copy(state = if (ok) "ok" else "failed"))
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    fun sendFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            scope.launch {
                try {
                    val dest = unique(File(sentDir, safeName(displayName(uri))))
                    app.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    val m = Msg(UUID.randomUUID().toString(), true, "file", name = dest.name,
                        size = dest.length(), path = dest.absolutePath, state = "sending")
                    add(m); upload(m)
                } catch (e: Exception) {
                    status.value = "Could not read file: ${e.message}"
                }
            }
        }
    }

    fun retry(m: Msg) {
        if (m.type != "file") return
        upd(m.id) { it.copy(state = if (m.mine) "sending" else "downloading", progress = 0f) }
        if (m.mine) upload(m) else download(m)
    }

    private fun upload(m: Msg) {
        scope.launch {
            val file = File(m.path)
            val len = file.length().coerceAtLeast(1)
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = file.length()
                override fun writeTo(sink: BufferedSink) {
                    file.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            sent += n
                            val p = (sent * 100 / len).toInt()
                            if (p != last) { last = p; upd(m.id) { it.copy(progress = p / 100f) } }
                        }
                    }
                }
            }
            try {
                val url = httpUrl(host, "upload").addQueryParameter("name", m.name).build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                }
                upd(m.id, true) { it.copy(state = "ok", progress = 1f) }
            } catch (e: Exception) {
                upd(m.id, true) { it.copy(state = "failed") }
            }
        }
    }

    // ---------- misc
    suspend fun discover(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 2500
                val d = "FTLINK_DISCOVER".toByteArray()
                s.send(DatagramPacket(d, d.size, InetAddress.getByName("255.255.255.255"), 8766))
                val p = DatagramPacket(ByteArray(512), 512)
                s.receive(p)
                val j = JSONObject(String(p.data, 0, p.length))
                (p.address.hostAddress ?: return@withContext null) to j.getInt("port")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearChat() { messages.value = emptyList(); save() }

    fun deleteFile(path: String) {
        File(path).delete()
        messages.update { l -> l.filterNot { it.path == path } }
        save()
    }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }
}
