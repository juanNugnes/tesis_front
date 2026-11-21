package com.juan.habitos.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.juan.habitos.BuildConfig
import com.juan.habitos.HabitosApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** ADB-only debug hook. It never logs credentials, manifests, source files or event data. */
class F12EvidenceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND) return
        if (!BuildConfig.F12_RAW_EVIDENCE_CAPTURE_ENABLED || !BuildConfig.F12_RAW_EVIDENCE_TRANSPORT_ENABLED) {
            Log.i(TAG, "status=DISABLED")
            return
        }
        val zone = ZoneId.systemDefault()
        val date = intent.getStringExtra(EXTRA_LOCAL_DATE)?.let(LocalDate::parse)
            ?: LocalDate.now(zone).minusDays(1)
        val revision = intent.getIntExtra(EXTRA_REVISION, 1)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as HabitosApplication
                val outcome = app.f12RawEvidenceManualCoordinator.captureAndDeliver(date, zone, revision)
                val receipt = outcome.transport.receipt
                Log.i(
                    TAG,
                    "status=${outcome.transport.status} capture_id=${outcome.transport.captureId ?: "none"} " +
                        "receipt_id=${receipt?.receiptId ?: "none"} revision=${receipt?.revision ?: revision} " +
                        "error_code=${outcome.transport.errorCode ?: "none"}",
                )
            } catch (failure: IllegalArgumentException) {
                Log.i(TAG, "status=INVALID_REQUEST error=${failure.javaClass.simpleName}")
            } catch (failure: Exception) {
                Log.e(TAG, "status=FAILED error=${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND = "com.juan.habitos.debug.SEND_F12_EVIDENCE"
        const val EXTRA_LOCAL_DATE = "local_date"
        const val EXTRA_REVISION = "revision"
        private const val TAG = "F12EvidenceDebug"
    }
}
