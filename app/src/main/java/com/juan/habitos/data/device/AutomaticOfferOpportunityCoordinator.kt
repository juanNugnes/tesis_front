package com.juan.habitos.data.device

import com.juan.habitos.data.local.AutomaticOfferOpportunityStore
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.first

enum class AutomaticOfferEvaluationStatus {
    DISABLED,
    MISSING_CONFIGURATION,
    NOT_ELIGIBLE,
    RECORDED,
    DUPLICATE,
}

data class AutomaticOfferEvaluationResult(
    val status: AutomaticOfferEvaluationStatus,
    val evidence: InteractiveOpportunityEvidence? = null,
)

class AutomaticOfferOpportunityCoordinator(
    private val enabled: Boolean,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val evaluator: OpenInteractiveIntervalEvaluator,
    private val evidenceStore: AutomaticOfferOpportunityStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun evaluate(): AutomaticOfferEvaluationResult {
        if (!enabled) return AutomaticOfferEvaluationResult(AutomaticOfferEvaluationStatus.DISABLED)
        val profile = profileStore.profile.first()
        val identity = identityStore.identity.first()
        val profileId = profile?.id
        val deviceId = identity.deviceId
        if (profile?.active != true || profileId == null || deviceId == null || identity.profileId != profileId) {
            return AutomaticOfferEvaluationResult(AutomaticOfferEvaluationStatus.MISSING_CONFIGURATION)
        }
        val evidence = evaluator.evaluate(profileId, deviceId, nowEpochMillis())
        if (evidence.state != InteractiveOpportunityState.ELIGIBLE) {
            return AutomaticOfferEvaluationResult(AutomaticOfferEvaluationStatus.NOT_ELIGIBLE, evidence)
        }
        val recorded = evidenceStore.recordIfAbsent(evidence)
        return AutomaticOfferEvaluationResult(
            if (recorded) AutomaticOfferEvaluationStatus.RECORDED else AutomaticOfferEvaluationStatus.DUPLICATE,
            evidence,
        )
    }
}
