package com.meshlink

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

enum class RadioStatus {
    READY,
    BLUETOOTH_DISABLED,
    LOCATION_DISABLED
}

object RadioReadinessHelper {

    fun isBluetoothEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) return false
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return false
        return adapter.isEnabled
    }

    fun isLocationRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt < Build.VERSION_CODES.TIRAMISU
    }

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    fun checkRadioReadiness(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): RadioStatus {
        if (!isBluetoothEnabled(context)) {
            return RadioStatus.BLUETOOTH_DISABLED
        }
        if (isLocationRequired(sdkInt) && !isLocationEnabled(context)) {
            return RadioStatus.LOCATION_DISABLED
        }
        return RadioStatus.READY
    }

    fun getFriendlyErrorMessage(context: Context, rawMessage: String): String {
        val trimmed = rawMessage.trim()
        val isBtOff = !isBluetoothEnabled(context)
        val isLocOff = isLocationRequired() && !isLocationEnabled(context)

        if (isBtOff) {
            return context.getString(R.string.radio_bluetooth_disabled)
        }
        if (isLocOff) {
            return context.getString(R.string.radio_location_disabled)
        }

        return when {
            trimmed.contains("8001") || trimmed.contains("STATUS_BLUETOOTH_ERROR") ->
                context.getString(R.string.radio_bluetooth_error)
            trimmed.contains("8002") || trimmed.contains("STATUS_WIFI_ERROR") ->
                context.getString(R.string.radio_wifi_error)
            trimmed.contains("8000") || trimmed.contains("STATUS_API_NOT_CONNECTED") ->
                context.getString(R.string.radio_play_services_error)
            trimmed.contains("8003") || trimmed.contains("STATUS_ERROR") ->
                context.getString(R.string.radio_nearby_error)
            else -> rawMessage
        }
    }
}
