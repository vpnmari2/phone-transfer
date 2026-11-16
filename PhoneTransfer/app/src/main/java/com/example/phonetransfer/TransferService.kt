package com.example.phonetransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

class TransferService : Service() {

    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8766
        const val ACTION_STOP = "com.example.phonetransfer.STOP"
        @Volatile var running = false
    }

    private var server: TransferServer? = null
    private var udp: DatagramSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var mcast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Util.prefs(this).edit().putBoolean("autostart", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server == null) startAll()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("pt", "Phone Transfer", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pt")
            .setContentTitle("Phone Transfer is running")
            .setContentText("Address: ${Util.localIp() ?: "no Wi-Fi"}:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun startAll() {
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonetransfer:srv")
                .apply { setReferenceCounted(false); acquire() }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "phonetransfer:wifi")
                .apply { setReferenceCounted(false); acquire() }
            mcast = wm.createMulticastLock("phonetransfer:mc")
                .apply { setReferenceCounted(false); acquire() }

            val root = Environment.getExternalStorageDirectory()
            server = TransferServer(this, root, PORT) { Util.pin(this) }.also { it.start() }
            startDiscovery()
            running = true
        } catch (e: Exception) {
            running = false
            stopSelf()
        }
    }

    private fun startDiscovery() {
        Thread {
            try {
                val s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                udp = s
                val buf = ByteArray(256)
                while (!s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (msg.startsWith("PHONETRANSFER_DISCOVER")) {
                        val reply = JSONObject().put("app", "phonetransfer")
                            .put("name", Build.MANUFACTURER + " " + Build.MODEL)
                            .put("port", PORT).toString().toByteArray()
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    override fun onDestroy() {
        running = false
        try { server?.stop() } catch (e: Exception) { }
        try { udp?.close() } catch (e: Exception) { }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Exception) { }
        try { if (mcast?.isHeld == true) mcast?.release() } catch (e: Exception) { }
        server = null
        super.onDestroy()
    }
}
