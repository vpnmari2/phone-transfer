package com.example.phonetransfer

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale

object Util {
    fun prefs(c: Context) = c.getSharedPreferences("pt", Context.MODE_PRIVATE)

    fun newPin(c: Context): String {
        val s = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        prefs(c).edit().putString("pin", s).apply()
        return s
    }

    fun pin(c: Context): String = prefs(c).getString("pin", null) ?: newPin(c)

    /** Best guess at this phone's Wi-Fi / hotspot IPv4 address. */
    fun localIp(): String? {
        val cands = ArrayList<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val h = a.hostAddress ?: continue
                        cands.add(Pair(ni.name, h))
                    }
                }
            }
        } catch (e: Exception) { }
        val wifi = cands.firstOrNull {
            it.first.startsWith("wlan") || it.first.startsWith("swlan") ||
                it.first.startsWith("ap") || it.first.startsWith("eth")
        }
        val other = cands.firstOrNull {
            !it.first.startsWith("rmnet") && !it.first.startsWith("tun") &&
                !it.first.startsWith("ccmni") && !it.first.startsWith("dummy")
        }
        return (wifi ?: other)?.second
    }
}
