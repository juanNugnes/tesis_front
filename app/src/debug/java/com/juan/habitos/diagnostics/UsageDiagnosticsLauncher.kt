package com.juan.habitos.diagnostics

import android.content.Context
import android.content.Intent

object UsageDiagnosticsLauncher {
    const val isAvailable = true

    fun open(context: Context) {
        context.startActivity(Intent(context, UsageDiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
