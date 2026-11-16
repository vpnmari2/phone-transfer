package com.example.phonetransfer

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Tiny HTTP server.
 *   GET /ping                      (no PIN)  -> info
 *   GET /list?path=/DCIM           -> folder listing (JSON)
 *   GET /download?path=/DCIM/a.jpg -> raw file bytes
 *   PUT /upload?path=/Download/a.zip  (body = raw bytes, Content-Length required)
 *   GET /partial?path=...          -> size of an unfinished upload + free space (for resume)
 * Large files: download supports "Range: bytes=N-", upload supports "X-Offset: N" (resume).
 * Every route except /ping needs header  X-Pin: <pin>.
 */
class TransferServer(
    private val ctx: Context,
    private val root: File,
    private val port: Int,
    private val pinProvider: () -> String
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val fails = HashMap<String, Pair<Int, Long>>() // ip -> (failures, lockedUntil)
    private val rootPath = root.canonicalPath
    private val activeUploads = HashSet<String>()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
        }.start()
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) { }
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            process(s)
        } catch (e: Exception) {
            // client disconnected or bad request
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun process(s: Socket) {
        s.soTimeout = 30000
        val input = BufferedInputStream(s.getInputStream(), 65536)
        val out = s.getOutputStream()

        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }

        val qi = target.indexOf('?')
        val route = if (qi >= 0) target.substring(0, qi) else target
        val query = HashMap<String, String>()
        if (qi >= 0) {
            for (kv in target.substring(qi + 1).split('&')) {
                val e = kv.indexOf('=')
                if (e > 0) query[kv.substring(0, e)] = URLDecoder.decode(kv.substring(e + 1), "UTF-8")
            }
        }

        if (route == "/ping") {
            json(out, 200, JSONObject().put("app", "phonetransfer")
                .put("name", Build.MANUFACTURER + " " + Build.MODEL).put("version", 1))
            return
        }

        val ip = s.inetAddress.hostAddress ?: "?"
        if (!authorized(ip, headers["x-pin"])) {
            json(out, 401, err("Wrong or missing PIN (5 wrong tries locks you out for 5 minutes)"))
            return
        }

        val rel = query["path"] ?: "/"
        val f = resolve(rel)
        if (f == null) {
            json(out, 400, err("Bad path"))
            return
        }

        when {
            method == "GET" && route == "/list" -> list(out, f, rel)
            method == "GET" && route == "/download" -> download(out, f, headers)
            method == "GET" && route == "/partial" -> partial(out, f)
            method == "PUT" && route == "/upload" -> upload(input, out, f, headers)
            else -> json(out, 404, err("Not found"))
        }
    }

    @Synchronized
    private fun authorized(ip: String, pin: String?): Boolean {
        val now = System.currentTimeMillis()
        val st = fails[ip]
        if (st != null && st.second > now) return false
        val ok = pin != null &&
            MessageDigest.isEqual(pin.toByteArray(), pinProvider().toByteArray())
        if (ok) {
            fails.remove(ip)
            return true
        }
        val count = (st?.first ?: 0) + 1
        fails[ip] = if (count >= 5) Pair(0, now + 5 * 60_000L) else Pair(count, 0L)
        return false
    }

    private fun resolve(rel: String): File? {
        val f = File(root, rel.trimStart('/')).canonicalFile
        val p = f.path
        return if (p == rootPath || p.startsWith("$rootPath/")) f else null
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun send(out: OutputStream, code: Int, ctype: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $ctype\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, o: JSONObject) =
        send(out, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun err(m: String) = JSONObject().put("error", m)

    private fun list(out: OutputStream, f: File, rel: String) {
        if (!f.isDirectory) {
            json(out, 404, err("Not a folder"))
            return
        }
        val kids = (f.listFiles() ?: emptyArray())
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val arr = JSONArray()
        for (k in kids) {
            arr.put(
                JSONObject().put("name", k.name).put("dir", k.isDirectory)
                    .put("size", if (k.isDirectory) 0L else k.length())
                    .put("mtime", k.lastModified() / 1000)
            )
        }
        json(out, 200, JSONObject().put("path", rel).put("items", arr))
    }

    private fun partial(out: OutputStream, f: File) {
        synchronized(activeUploads) {
            if (activeUploads.contains(f.path)) {
                json(out, 409, err("Previous upload still closing, retry"))
                return
            }
        }
        val tmp = File(f.parentFile, f.name + ".part")
        val free = generateSequence(f.parentFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L
        json(out, 200, JSONObject().put("size", if (tmp.isFile) tmp.length() else 0L).put("free", free))
    }

    private fun download(out: OutputStream, f: File, headers: Map<String, String>) {
        if (!f.isFile) {
            json(out, 404, err("No such file"))
            return
        }
        val total = f.length()
        var start = 0L
        val range = headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            start = range.substring(6).substringBefore('-').toLongOrNull() ?: 0L
            if (start < 0 || start >= total) {
                json(out, 416, err("Range not satisfiable"))
                return
            }
        }
        val h = StringBuilder()
        h.append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        h.append("Content-Type: application/octet-stream\r\n")
        h.append("Content-Length: ${total - start}\r\n")
        if (start > 0) h.append("Content-Range: bytes $start-${total - 1}/$total\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray())
        java.io.FileInputStream(f).use { fis ->
            if (start > 0) fis.channel.position(start)
            fis.copyTo(out, 262144)
        }
        out.flush()
    }

    private fun upload(input: InputStream, out: OutputStream, f: File, headers: Map<String, String>) {
        val len = headers["content-length"]?.toLongOrNull()
        if (len == null || len < 0) {
            json(out, 400, err("Content-Length required"))
            return
        }
        if (f.isDirectory) {
            json(out, 400, err("Target is a folder"))
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        val offset = headers["x-offset"]?.toLongOrNull() ?: 0L
        if (offset > 0) {
            val have = if (tmp.isFile) tmp.length() else -1L
            if (have != offset) {
                json(out, 409, err("Resume mismatch: phone has $have bytes"))
                return
            }
        }
        val free = f.parentFile?.usableSpace ?: 0L
        if (free < len + 50L * 1024 * 1024) {
            json(out, 400, err("Not enough free space on the phone"))
            return
        }
        synchronized(activeUploads) {
            if (!activeUploads.add(f.path)) {
                json(out, 409, err("Another upload to this file is running"))
                return
            }
        }
        try {
            // If the connection drops, the .part file is kept so the PC can resume.
            var left = len
            java.io.FileOutputStream(tmp, offset > 0).buffered(262144).use { o ->
                val buf = ByteArray(262144)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException()
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        } finally {
            synchronized(activeUploads) { activeUploads.remove(f.path) }
        }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            tmp.delete()
            json(out, 500, err("Could not save file"))
            return
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        json(out, 200, JSONObject().put("ok", true).put("size", f.length()))
    }
}
