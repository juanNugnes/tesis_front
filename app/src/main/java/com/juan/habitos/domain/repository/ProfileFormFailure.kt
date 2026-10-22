package com.juan.habitos.domain.repository

enum class ProfileFormFailure { ENDPOINT_UNAVAILABLE, PROFILE_NOT_FOUND, UNCLASSIFIED_NOT_FOUND, PROFILE_CHANGED }

/** Only local, fixed messages are exposed. Never show a server body to the family. */
class ProfileFormException(val reason: ProfileFormFailure, cause: Throwable? = null) : Exception(when (reason) {
    ProfileFormFailure.ENDPOINT_UNAVAILABLE -> "Esta función no está disponible en el servidor. Es necesario revisar la integración o el despliegue."
    ProfileFormFailure.PROFILE_NOT_FOUND -> "El perfil no existe en el servidor. Tus respuestas siguen en el formulario."
    ProfileFormFailure.UNCLASSIFIED_NOT_FOUND -> "El servidor no encontró el recurso solicitado. No podemos confirmar que el perfil haya sido eliminado."
    ProfileFormFailure.PROFILE_CHANGED -> "Cambió el perfil seleccionado. Volvé a abrir sus preferencias; no se enviaron tus cambios a otro perfil."
}, cause)
