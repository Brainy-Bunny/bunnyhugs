package com.ssbmedia.twogether.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object BlePermissions {

    /** Permissions required to scan/advertise/connect over BLE, dependent on API level. */
    fun required(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    fun hasAll(context: Context, permissions: Array<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun hasBlePermissions(context: Context): Boolean = hasAll(context, required())

    fun hasNotificationPermission(context: Context): Boolean {
        val perm = notificationPermission() ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** On API < 31, BLE scanning requires both the ACCESS_FINE_LOCATION permission (see [required])
     * AND the system Location Services toggle to actually be turned on - the permission alone isn't
     * enough. Without checking this, startScan() silently "succeeds" but never delivers any result,
     * indistinguishable to the user from a hardware fault, with no explanation anywhere in the app. On
     * API 31+ this isn't required (BLUETOOTH_SCAN covers it independently of Location Services). */
    fun needsLocationServicesEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return !LocationManagerCompat.isLocationEnabled(locationManager)
    }
}
