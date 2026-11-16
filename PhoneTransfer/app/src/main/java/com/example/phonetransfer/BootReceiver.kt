package com.example.phonetransfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED &&
            Util.prefs(c).getBoolean("autostart", true)
        ) {
            try {
                c.startForegroundService(Intent(c, TransferService::class.java))
            } catch (e: Exception) { }
        }
    }
}
