package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val name: String = "",
    val age: Int? = null,
    val avatar: String? = null,
    val loaded: Boolean = false,
    val submitting: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
) {
    val valid: Boolean get() = name.trim().isNotEmpty() && age in 6..12 && !avatar.isNullOrBlank()
}

class EditProfileViewModel(
    private val repository: ProfileRepository,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUiState())
    val state = _state.asStateFlow()
    private var originalProfile: Profile? = null

    init {
        viewModelScope.launch {
            val profile = repository.profile.first()
            originalProfile = profile
            _state.value = profile?.let {
                EditProfileUiState(name = it.name, age = it.age, avatar = it.avatar, loaded = true)
            } ?: EditProfileUiState(loaded = true, errorMessage = "No encontramos el perfil para editar.")
        }
    }

    fun name(value: String) = _state.update { it.copy(name = value, saved = false, errorMessage = null) }
    fun age(value: Int) = _state.update { it.copy(age = value, saved = false, errorMessage = null) }
    fun avatar(value: String) = _state.update { it.copy(avatar = value, saved = false, errorMessage = null) }

    fun save(onSaved: () -> Unit = {}) {
        val current = state.value
        val original = originalProfile ?: return
        if (!current.loaded || !current.valid || current.submitting) return
        _state.update { it.copy(submitting = true, saved = false, errorMessage = null) }
        val edited = original.copy(
            name = current.name.trim(),
            age = current.age!!,
            avatar = current.avatar!!,
        )
        viewModelScope.launch {
            try {
                val saved = if (demoMode) {
                    repository.save(edited)
                    edited
                } else {
                    repository.update(edited)
                }
                originalProfile = saved
                _state.value = EditProfileUiState(
                    name = saved.name,
                    age = saved.age,
                    avatar = saved.avatar,
                    loaded = true,
                    saved = true,
                )
                onSaved()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        submitting = false,
                        saved = false,
                        errorMessage = "No pudimos guardar los cambios. Intentá nuevamente.",
                    )
                }
            }
        }
    }
}
