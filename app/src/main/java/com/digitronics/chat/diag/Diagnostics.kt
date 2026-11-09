package com.digitronics.chat.diag

/** What the Diagnostics screen may copy. Deliberately has no field for tokens, names or messages. */
data class DiagnosticsInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val buildCommit: String,
    val buildType: String,
    val sdkInt: Int,
    val deviceModel: String,
    val signedIn: Boolean,
    val pushState: String,
    val notificationPermission: String,
    val realtimeState: String,
    val updateState: String,
    val updateCheckingConfigured: Boolean,
)

object Diagnostics {
    fun summary(info: DiagnosticsInfo): String = redact(
        buildString {
            appendLine("${info.appName} diagnostics")
            appendLine("App version: ${info.versionName} (${info.versionCode}), ${info.buildType}, commit ${info.buildCommit}")
            appendLine("Android SDK: ${info.sdkInt}; device: ${info.deviceModel}")
            appendLine("Signed in: ${if (info.signedIn) "yes" else "no"}")
            appendLine("Push: ${info.pushState}; notification permission: ${info.notificationPermission}")
            appendLine("Realtime: ${info.realtimeState}")
            append("Updates: ${info.updateState}; update checking configured: ${if (info.updateCheckingConfigured) "yes" else "no"}")
        },
    )

    private val TOKEN = Regex("dcm_(at|rt|ac)_[A-Za-z0-9_-]+")
    private val BEARER = Regex("(?i)bearer\\s+\\S+")
    private val LONG_SECRET = Regex("[A-Za-z0-9_\\-+/=:]{40,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Belt and braces: strips anything token- or address-shaped that could have slipped in. */
    fun redact(text: String): String = text
        .replace(TOKEN, "[redacted]")
        .replace(BEARER, "Bearer [redacted]")
        .replace(EMAIL, "[redacted]")
        .replace(LONG_SECRET, "[redacted]")
}
