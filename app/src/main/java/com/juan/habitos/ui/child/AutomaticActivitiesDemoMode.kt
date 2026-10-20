package com.juan.habitos.ui.child

internal fun automaticActivitiesDemoEnabled(isDebug: Boolean, requested: Boolean = false): Boolean =
    isDebug && requested
