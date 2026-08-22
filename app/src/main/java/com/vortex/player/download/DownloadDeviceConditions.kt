package com.vortex.player.download

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

data class DownloadDeviceSnapshot(
    val connected: Boolean,
    val wifi: Boolean,
    val charging: Boolean,
    val batteryPercent: Int,
    val lowMemory: Boolean,
    val thermalStatus: Int,
    val minuteOfDay: Int
)

object DownloadDeviceConditions {

    fun snapshot(context: Context): DownloadDeviceSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100

        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: 0
        } else {
            0
        }
        val now = Calendar.getInstance()
        return DownloadDeviceSnapshot(
            connected = connected,
            wifi = wifi,
            charging = charging,
            batteryPercent = percent,
            lowMemory = memory.lowMemory,
            thermalStatus = thermal,
            minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        )
    }

    fun blockReason(policy: DownloadPolicy, snapshot: DownloadDeviceSnapshot): String? = when {
        !snapshot.connected -> "Esperando conexión a internet"
        policy.wifiOnly && !snapshot.wifi -> "Esperando una red Wi-Fi"
        policy.chargingOnly && !snapshot.charging -> "Esperando que conectes el cargador"
        !policy.schedule.allows(snapshot.minuteOfDay) ->
            "Fuera del horario ${policy.schedule.label}"
        else -> null
    }

    fun adaptiveLimit(requested: Int, enabled: Boolean, snapshot: DownloadDeviceSnapshot): Int {
        val safe = DownloadConcurrency.clamp(requested)
        if (!enabled) return safe
        return when {
            snapshot.lowMemory -> 1
            snapshot.thermalStatus >= THERMAL_SEVERE -> 1
            snapshot.thermalStatus >= THERMAL_MODERATE -> minOf(safe, 2)
            !snapshot.charging && snapshot.batteryPercent <= 15 -> 1
            !snapshot.charging && snapshot.batteryPercent <= 30 -> minOf(safe, 2)
            else -> safe
        }
    }

    // Valores estables de PowerManager para poder probar la política sin depender de API 29.
    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
}
