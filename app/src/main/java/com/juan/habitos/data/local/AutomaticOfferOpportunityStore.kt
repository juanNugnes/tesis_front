package com.juan.habitos.data.local

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.juan.habitos.data.device.InteractiveOpportunityEvidence
import com.juan.habitos.data.device.InteractiveOpportunityState
import com.juan.habitos.domain.model.PendingAutomaticOffer

interface AutomaticOfferOpportunityStore {
    /** Returns false when this exact interval was already recorded. */
    fun recordIfAbsent(evidence: InteractiveOpportunityEvidence): Boolean
    fun pending(): List<InteractiveOpportunityEvidence>
    fun complete(intervalId: String)
    fun savePendingOffer(offer: PendingAutomaticOffer)
    fun pendingOffer(): PendingAutomaticOffer?
    fun updatePendingOffer(transform: (PendingAutomaticOffer?) -> PendingAutomaticOffer?)
}

class SharedPreferencesAutomaticOfferOpportunityStore(context: Context) : AutomaticOfferOpportunityStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = GsonBuilder().serializeNulls().create()
    private val listType = object : TypeToken<List<InteractiveOpportunityEvidence>>() {}.type

    @Synchronized
    override fun recordIfAbsent(evidence: InteractiveOpportunityEvidence): Boolean {
        require(evidence.state == InteractiveOpportunityState.ELIGIBLE && evidence.intervalId != null)
        val current = read()
        if (current.any { it.intervalId == evidence.intervalId }) return false
        val updated = (current + evidence).takeLast(MAX_PENDING_EVIDENCE)
        check(preferences.edit().putString(PENDING_KEY, gson.toJson(updated)).commit()) {
            "Could not persist automatic-offer opportunity evidence"
        }
        return true
    }

    @Synchronized
    override fun pending(): List<InteractiveOpportunityEvidence> = read()

    @Synchronized
    override fun complete(intervalId: String) {
        write(read().filterNot { it.intervalId == intervalId })
    }

    @Synchronized
    override fun savePendingOffer(offer: PendingAutomaticOffer) {
        check(preferences.edit().putString(OFFER_KEY, gson.toJson(offer)).commit())
    }

    @Synchronized
    override fun pendingOffer(): PendingAutomaticOffer? = preferences.getString(OFFER_KEY, null)?.let {
        runCatching { gson.fromJson(it, PendingAutomaticOffer::class.java) }.getOrNull()
    }

    @Synchronized
    override fun updatePendingOffer(transform: (PendingAutomaticOffer?) -> PendingAutomaticOffer?) {
        val updated = transform(pendingOffer())
        val editor = preferences.edit()
        if (updated == null) editor.remove(OFFER_KEY) else editor.putString(OFFER_KEY, gson.toJson(updated))
        check(editor.commit())
    }

    private fun read(): List<InteractiveOpportunityEvidence> = preferences.getString(PENDING_KEY, null)
        ?.let { encoded -> runCatching { gson.fromJson<List<InteractiveOpportunityEvidence>>(encoded, listType) }.getOrNull() }
        .orEmpty()

    private fun write(values: List<InteractiveOpportunityEvidence>) {
        check(preferences.edit().putString(PENDING_KEY, gson.toJson(values)).commit())
    }

    private companion object {
        const val PREFERENCES_NAME = "automatic_offer_opportunities"
        const val PENDING_KEY = "pending_evidence_v1"
        const val OFFER_KEY = "pending_offer_v1"
        const val MAX_PENDING_EVIDENCE = 64
    }
}
