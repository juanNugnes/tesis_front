package com.juan.habitos.ui.components

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.juan.habitos.domain.model.AppUsage

private val packageNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
private val packageInfrastructureSegments = setOf("com", "org", "net", "android", "app", "apps", "mobile", "client", "release")
private const val GENERIC_APPLICATION_ICON = "📱"

// Exact package matches only. These are neutral symbols, not downloaded or third-party logo assets.
private val knownApplicationIcons = mapOf(
    "com.google.android.youtube" to "▶️",
    "com.google.android.apps.youtube.kids" to "▶️",
    "com.whatsapp" to "💬",
    "com.whatsapp.w4b" to "💬",
    "com.roblox.client" to "🎮",
    "org.khanacademy.android" to "📚",
)

internal fun packageDerivedDisplayName(packageName: String): String {
    fun readableSegment(segment: String): String {
        val separated = segment
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=\\d+$)"), " ")
            .trim()
        return separated.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { token -> token.lowercase().replaceFirstChar { it.titlecase() } }
    }

    val identifyingSegments = packageName.split('.')
        .map(String::trim)
        .filter { it.isNotEmpty() && it.lowercase() !in packageInfrastructureSegments }
        .takeLast(2)
        .map(::readableSegment)
        .filter(String::isNotBlank)
    return identifyingSegments.joinToString(" ").takeIf(String::isNotBlank) ?: "Aplicación"
}

internal fun resolveApplicationDisplayName(reportedName: String, packageName: String, installedLabel: String?): String {
    val localLabel = installedLabel?.trim().orEmpty()
    if (localLabel.isNotEmpty() && !packageNamePattern.matches(localLabel)) return localLabel
    val reported = reportedName.trim()
    if (reported.isNotEmpty() && reported != packageName && !packageNamePattern.matches(reported)) return reported
    return packageDerivedDisplayName(packageName)
}

internal fun resolveApplicationDisplayName(reportedName: String, packageName: String, installedLabelLookup: () -> String?): String =
    resolveApplicationDisplayName(reportedName, packageName, runCatching(installedLabelLookup).getOrNull())

internal fun applicationFallbackIcon(packageName: String, reportedIcon: String?): String {
    val supplied = reportedIcon?.trim().orEmpty()
    return supplied.takeIf { it.isNotEmpty() && it != GENERIC_APPLICATION_ICON }
        ?: knownApplicationIcons[packageName]
        ?: GENERIC_APPLICATION_ICON
}

private data class InstalledApplicationVisual(
    val label: String?,
    val icon: Drawable?,
)

private fun PackageManager.applicationVisual(packageName: String): InstalledApplicationVisual {
    if (packageName.isBlank()) return InstalledApplicationVisual(null, null)
    return try {
        @Suppress("DEPRECATION")
        val applicationInfo = getApplicationInfo(packageName, 0)
        InstalledApplicationVisual(
            label = runCatching { applicationInfo.loadLabel(this).toString().takeIf(String::isNotBlank) }.getOrNull(),
            icon = runCatching { applicationInfo.loadIcon(this) }.getOrNull(),
        )
    } catch (_: PackageManager.NameNotFoundException) {
        InstalledApplicationVisual(null, null)
    } catch (_: SecurityException) {
        InstalledApplicationVisual(null, null)
    } catch (exception: RuntimeException) {
        Log.w("ApplicationVisual", "Unable to resolve package metadata for $packageName", exception)
        InstalledApplicationVisual(null, null)
    }
}

internal data class ApplicationVisualPresentation(
    val displayName: String,
    val installedIcon: ImageBitmap?,
    val fallbackIcon: String,
)

@Composable internal fun rememberApplicationVisualPresentation(app: AppUsage): ApplicationVisualPresentation {
    val packageManager = LocalContext.current.packageManager
    val installed = remember(packageManager, app.packageName) { packageManager.applicationVisual(app.packageName) }
    val bitmap = remember(installed.icon) { runCatching { installed.icon?.toBitmap()?.asImageBitmap() }.getOrNull() }
    return remember(app.name, app.packageName, app.icon, installed.label, bitmap) {
        ApplicationVisualPresentation(
            displayName = resolveApplicationDisplayName(app.name, app.packageName, installed.label),
            installedIcon = bitmap,
            fallbackIcon = applicationFallbackIcon(app.packageName, app.icon),
        )
    }
}
