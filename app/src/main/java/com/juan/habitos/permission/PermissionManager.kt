package com.juan.habitos.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

interface PermissionManager { fun request(index: Int) }
class AndroidPermissionManager(private val context: Context) : PermissionManager {
    override fun request(index: Int) {
        val intent = when (index) {
            0 -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            1 -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            2 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> Intent(Settings.ACTION_SECURITY_SETTINGS) // TODO: register a DeviceAdminReceiver after defining the desired blocking policy.
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
