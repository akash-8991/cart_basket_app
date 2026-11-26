package com.smartcart.app.security

import android.content.Context
import android.os.Build
import com.smartcart.app.BuildConfig
import com.smartcart.model.DeviceInfo
import java.util.UUID

/**
 * A random ID for this installation (not a hardware identifier). The backend binds refresh tokens to it,
 * so a stolen token is useless from another install (threat model A-03). Reinstalling makes a new one.
 */
class DeviceIdentity(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    fun info() = DeviceInfo(deviceId = deviceId, appVersion = BuildConfig.VERSION_NAME, osVersion = Build.VERSION.RELEASE, deviceModel = Build.MODEL.take(64))
}
