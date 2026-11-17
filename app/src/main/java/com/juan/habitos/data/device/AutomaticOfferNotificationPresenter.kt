package com.juan.habitos.data.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.juan.habitos.MainActivity
import com.juan.habitos.domain.repository.AutomaticOfferRepository
import kotlinx.coroutines.flow.first

class AutomaticOfferNotificationPresenter(
    private val context: Context,
    private val repository: AutomaticOfferRepository,
) {
    suspend fun presentIfPossible(): Boolean {
        val offer = repository.pendingOffer.first() ?: return false
        if (offer.delivered) return false
        if (offer.presentedChannel != null) {
            repository.confirmDelivered(offer.presentedChannel)
            return true
        }
        if (offer.channel != "notification") return false
        if (offer.activities.none { !it.title.isNullOrBlank() }) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sugerencias de actividades", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val firstTitle = offer.activities.firstNotNullOf { it.title?.takeIf(String::isNotBlank) }
        manager.notify(
            offer.coordinatorOfferId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Momento para una actividad")
                .setContentText(firstTitle)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build(),
        )
        repository.confirmDelivered("notification")
        return true
    }

    private companion object { const val CHANNEL_ID = "automatic_activity_offers" }
}
