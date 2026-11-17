package com.juan.habitos.domain.model

data class AutomaticOfferActivity(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val materials: List<String>? = null,
    val steps: List<String>? = null,
)

data class PendingAutomaticOffer(
    val profileId: String,
    val requestId: String,
    val coordinatorOfferId: String,
    val activityOfferId: String,
    val channel: String,
    val activities: List<AutomaticOfferActivity>,
    val presentedChannel: String? = null,
    val delivered: Boolean = false,
)

data class AutomaticOfferSettings(
    val automaticEnabled: Boolean,
    val sharedPhone: Boolean,
)
