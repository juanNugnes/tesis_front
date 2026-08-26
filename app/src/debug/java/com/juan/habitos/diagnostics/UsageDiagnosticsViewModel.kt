package com.juan.habitos.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.data.device.UsageDiagnosticSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UsageDiagnosticsUiState(
    val hasAccess: Boolean = false,
    val range: DiagnosticRange = DiagnosticRange.LAST_30_MINUTES,
    val loading: Boolean = false,
    val snapshot: UsageDiagnosticSnapshot? = null,
    val error: String? = null,
)

class UsageDiagnosticsViewModel(private val repository: UsageDiagnosticsRepository) : ViewModel() {
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
}
