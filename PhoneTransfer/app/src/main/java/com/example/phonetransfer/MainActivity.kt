package com.example.phonetransfer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var pinView: TextView
    private lateinit var perms: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 72, 32, 48)
        }
        fun tv(size: Float, bold: Boolean = false, top: Int = 12) = TextView(this).apply {
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, top, 0, 0)
            root.addView(this)
        }
        fun btn(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
            root.addView(this)
        }

        tv(24f, true, 0).text = "Phone Transfer"
        status = tv(16f, true, 16)
        address = tv(16f)
        pinView = tv(30f, true, 20)
        tv(12f).text = "Type this address and PIN into the PC program (it can also find the phone by itself)."
        toggle = btn("Start service") { toggleService() }
        btn("New PIN") { Util.newPin(this); refresh() }
        perms = tv(13f, false, 20)
        btn("Allow access to all files") { requestFiles() }
        btn("Keep running in background (battery)") { requestBattery() }
        tv(12f, false, 20).text =
            "Use only on Wi-Fi you trust. Anyone with the PIN can browse and copy files on this phone. " +
                "Don't put this app to sleep in Device care. Use Stop here or in the notification to turn it off."

        setContentView(ScrollView(this).apply { addView(root) })

        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (Util.prefs(this).getBoolean("autostart", true) && !TransferService.running) startSvc()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun startSvc() {
        try {
            startForegroundService(Intent(this, TransferService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleService() {
        if (TransferService.running) {
            startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP))
        } else {
            Util.prefs(this).edit().putBoolean("autostart", true).apply()
            startSvc()
        }
        handler.postDelayed({ refresh() }, 600)
    }

    private fun hasFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        val on = TransferService.running
        status.text = if (on) "Service: RUNNING (always on)" else "Service: stopped"
        toggle.text = if (on) "Stop service" else "Start service"
        val ip = Util.localIp()
        address.text = if (ip != null) "Address: $ip   Port: ${TransferService.PORT}"
        else "No Wi-Fi or hotspot connection found"
        pinView.text = "PIN: ${Util.pin(this)}"
        val pm = getSystemService(PowerManager::class.java)
        perms.text = "All-files access: " + (if (hasFiles()) "OK" else "NOT GRANTED") +
            "\nBattery unrestricted: " + (if (pm.isIgnoringBatteryOptimizations(packageName)) "OK" else "no")
    }
}
