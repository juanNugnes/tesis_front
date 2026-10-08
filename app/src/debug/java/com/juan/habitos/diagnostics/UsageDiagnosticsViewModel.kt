package com.juan.habitos.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.data.device.UsageDiagnosticSnapshot
import com.juan.habitos.data.device.DailyDeviceInteractiveScreenObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UsageDiagnosticsUiState(
    val hasAccess: Boolean = false,
    val range: DiagnosticRange = DiagnosticRange.LAST_30_MINUTES,
    val loading: Boolean = false,
    val snapshot: UsageDiagnosticSnapshot? = null,
    val error: String? = null,
    val historicalExporting: Boolean = false,
    val historicalJson: String? = null,
    val historicalIncludesOverlaps: Boolean = false,
    val historicalError: String? = null,
    val observationCalculating: Boolean = false,
    val interactiveScreenObservation: DailyDeviceInteractiveScreenObservation? = null,
    val observationJson: String? = null,
    val observationError: String? = null,
)

class UsageDiagnosticsViewModel(
    private val repository: UsageDiagnosticsRepository,
    private val profileId: suspend () -> String? = { null },
    private val deviceId: suspend () -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(UsageDiagnosticsUiState())
    val state = _state.asStateFlow()

    init { refresh() }
    fun selectRange(range: DiagnosticRange) { _state.update { it.copy(range = range) }; refresh() }
    fun refresh() {
        val access = repository.hasAccess()
        _state.update { it.copy(hasAccess = access, snapshot = if (access) it.snapshot else null, error = null) }
        if (!access) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.load(_state.value.range) }
                .onSuccess { result -> _state.update { it.copy(loading = false, snapshot = result) } }
                .onFailure { throwable -> _state.update { it.copy(loading = false, error = throwable.message ?: throwable.javaClass.simpleName) } }
        }
    }
    fun exportText(): String? = state.value.snapshot?.let { repository.export(state.value.range, it) }

    fun generateHistoricalJson(includeOverlappingRuns: Boolean) {
        if (_state.value.historicalExporting) return
        _state.update { it.copy(historicalExporting = true, historicalError = null) }
        viewModelScope.launch {
            try {
                val json = repository.generateHistoricalJson(profileId(), deviceId(), includeOverlappingRuns)
                _state.update {
                    it.copy(
                        historicalExporting = false,
                        historicalJson = json,
                        historicalIncludesOverlaps = includeOverlappingRuns,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        historicalExporting = false,
                        historicalError = exception.message ?: exception.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun historicalJson(): String? = state.value.historicalJson

    fun calculateInteractiveScreenObservation() {
        if (_state.value.observationCalculating) return
        _state.update { it.copy(observationCalculating = true, observationError = null) }
        viewModelScope.launch {
            try {
                val observation = repository.calculateInteractiveScreenObservation(profileId(), deviceId())
                _state.update {
                    it.copy(
                        observationCalculating = false,
                        interactiveScreenObservation = observation,
                        observationJson = repository.observationJson(observation),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        observationCalculating = false,
                        observationError = exception.message ?: exception.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun observationJson(): String? = state.value.observationJson
}
