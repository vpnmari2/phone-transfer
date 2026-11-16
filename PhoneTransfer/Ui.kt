@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.ftlink.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ helpers
fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: ""

fun kindOf(name: String): String {
    val m = mimeOf(name)
    return when {
        m.startsWith("image/") -> "image"
        m.startsWith("video/") -> "video"
        else -> "other"
    }
}

fun fmtSize(b: Long): String {
    var n = b.toDouble()
    for (u in listOf("B", "KB", "MB", "GB")) {
        if (n < 1024) return if (u == "B") "${n.toLong()} B" else "%.1f %s".format(n, u)
        n /= 1024
    }
    return "%.1f TB".format(n)
}

fun openExternal(ctx: Context, path: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(path).ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

// --------------------------------------------------------------------- root
@Composable
fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val status by Hub.status.collectAsState()
    Scaffold(
        topBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp, 10.dp)
            ) {
                Text("FTLink", style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    "Chat" to Icons.Default.ChatBubble,
                    "Gallery" to Icons.Default.PhotoLibrary,
                    "Settings" to Icons.Default.Settings
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatScreen { viewing = it }
                1 -> GalleryScreen { viewing = it }
                else -> SettingsScreen()
            }
        }
    }
    viewing?.let { Viewer(it) { viewing = null } }
}

// --------------------------------------------------------------------- chat
@Composable
fun ChatScreen(onView: (String) -> Unit) {
    val msgs by Hub.messages.collectAsState()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        Hub.sendFiles(it)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs, key = { it.id }) { Bubble(it, onView) }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Attach files") }
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 4
            )
            IconButton({ Hub.sendChat(text); text = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
fun Bubble(m: Msg, onView: (String) -> Unit) {
    val ctx = LocalContext.current
    val bg = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 290.dp)) {
            Column(Modifier.padding(8.dp)) {
                if (m.type == "chat") {
                    Text(m.text)
                } else {
                    val kind = kindOf(m.name)
                    val ready = m.state == "ok" && File(m.path).exists()
                    Column(
                        Modifier.clickable(enabled = ready || m.state == "failed") {
                            when {
                                m.state == "failed" -> Hub.retry(m)
                                kind == "other" -> openExternal(ctx, m.path)
                                else -> onView(m.path)
                            }
                        }
                    ) {
                        if (ready && kind != "other") {
                            Thumb(m.path, kind, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
                        } else {
                            Icon(Icons.Default.Description, null, Modifier.size(40.dp))
                        }
                        Text(m.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(m.size), style = MaterialTheme.typography.bodySmall)
                        if (m.state == "sending" || m.state == "downloading") {
                            LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.ts))
                val st = when (m.state) {
                    "sending" -> " · sending…"
                    "downloading" -> " · receiving…"
                    "failed" -> if (m.type == "file") " · failed – tap to retry" else " · not sent (offline)"
                    else -> ""
                }
                Text(
                    time + st, style = MaterialTheme.typography.labelSmall,
                    color = if (m.state == "failed") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun Thumb(path: String, kind: String, modifier: Modifier) {
    Box(modifier) {
        AsyncImage(File(path), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        if (kind == "video") {
            Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
        }
    }
}

// ------------------------------------------------------------------ gallery
@Composable
fun GalleryScreen(onView: (String) -> Unit) {
    val ctx = LocalContext.current
    val msgs by Hub.messages.collectAsState()
    var sub by remember { mutableIntStateOf(0) }
    var del by remember { mutableStateOf<File?>(null) }
    // everything stored in the app folders, even if chat history was cleared
    val files = remember(msgs) {
        (Hub.receivedDir.listFiles().orEmpty() + Hub.sentDir.listFiles().orEmpty())
            .filter { it.isFile }.sortedByDescending { it.lastModified() }
    }
    val kinds = listOf("image", "video", "other")
    val titles = listOf("Images", "Videos", "Files")
    val shown = files.filter { kindOf(it.name) == kinds[sub] }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            titles.forEachIndexed { i, t ->
                Tab(sub == i, { sub = i }, text = { Text("$t (${files.count { kindOf(it.name) == kinds[i] }})") })
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here yet") }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(110.dp), contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(shown, key = { it.absolutePath }) { f ->
                    Box(
                        Modifier.aspectRatio(1f).combinedClickable(
                            onClick = { if (sub == 2) openExternal(ctx, f.path) else onView(f.path) },
                            onLongClick = { del = f })
                    ) {
                        if (sub == 2) {
                            Column(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Description, null, Modifier.size(36.dp))
                                Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall)
                                Text(fmtSize(f.length()), style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Thumb(f.path, kinds[sub], Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
    del?.let { f ->
        AlertDialog(
            onDismissRequest = { del = null },
            confirmButton = { TextButton({ Hub.deleteFile(f.path); del = null }) { Text("Delete") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } },
            title = { Text("Delete file?") }, text = { Text(f.name) }
        )
    }
}

// ------------------------------------------------------------------- viewer
@Composable
fun Viewer(path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (kindOf(path) == "video") {
                AndroidView(
                    factory = { c ->
                        FrameLayout(c).also { root ->
                            val v = VideoView(c)
                            val mc = MediaController(c)
                            mc.setAnchorView(root)
                            v.setMediaController(mc)
                            v.setVideoPath(path)
                            v.setOnPreparedListener { it.start() }
                            root.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                var scale by remember { mutableFloatStateOf(1f) }
                var off by remember { mutableStateOf(Offset.Zero) }
                val st = rememberTransformableState { z, p, _ ->
                    scale = (scale * z).coerceIn(1f, 6f); off += p
                }
                AsyncImage(
                    File(path), null,
                    Modifier.fillMaxSize().transformable(st)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                    contentScale = ContentScale.Fit
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton({ openExternal(ctx, path) }) { Icon(Icons.Default.Share, "Open with", tint = Color.White) }
                IconButton(onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
        }
    }
}

// ----------------------------------------------------------------- settings
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(Hub.host) }
    var port by remember { mutableStateOf(Hub.port.toString()) }
    var token by remember { mutableStateOf(Hub.token) }
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Connect to your PC", style = MaterialTheme.typography.titleMedium)
        Text("Run ftlink_pc.py on the PC (same Wi-Fi). Enter the address and token shown in its window.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("PC IP address") })
        OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Token") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                scope.launch {
                    msg = "Searching…"
                    val r = Hub.discover()
                    if (r != null) { host = r.first; port = r.second.toString(); msg = "Found PC at ${r.first}" }
                    else msg = "PC not found – check firewall or type the IP"
                }
            }) { Text("Find PC") }
            Button({
                Hub.host = host; Hub.port = port.toIntOrNull() ?: 8765; Hub.token = token
                Hub.restart(); msg = "Saved – connecting…"
            }) { Text("Save & connect") }
        }
        if (msg.isNotEmpty()) Text(msg)
        HorizontalDivider()
        Text("Always-on", style = MaterialTheme.typography.titleMedium)
        Text("FTLink runs as a foreground service and restarts after reboot. Exempt it from battery " +
            "optimization so Android never pauses the connection.", style = MaterialTheme.typography.bodySmall)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        Button({
            try {
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Toast.makeText(ctx, "Already unrestricted", Toast.LENGTH_SHORT).show()
                } else {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"))
                    )
                }
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }) { Text("Disable battery optimization") }
        HorizontalDivider()
        OutlinedButton({ Hub.clearChat() }) { Text("Clear chat history (keeps files)") }
        Text("Files are stored in:\n${Hub.receivedDir.parentFile?.absolutePath}", style = MaterialTheme.typography.bodySmall)
    }
}
