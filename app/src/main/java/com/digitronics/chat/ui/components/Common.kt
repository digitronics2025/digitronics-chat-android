package com.digitronics.chat.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.digitronics.chat.BuildConfig
import com.digitronics.chat.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The launcher glyph on a teal disc — used on sign-in and About. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(0xFF0F766E)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size * 1.5f))
    }
}

@Composable
fun Avatar(title: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Opens an https link: our own origin and the developer's site in a Custom Tab,
 * anything else is refused (links come from code, never from push data).
 */
fun openTrustedUrl(context: Context, url: String) {
    val parsed = url.toHttpUrlOrNull() ?: return
    if (parsed.scheme != "https" || parsed.host !in trustedHosts()) return
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (e: ActivityNotFoundException) {
        openInBrowser(context, url)
    }
}

/** Opens a link in the full browser (used for the APK download, which Android installs from there). */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser on the device; nothing more we can do safely.
    }
}

private fun trustedHosts(): Set<String> = setOfNotNull(
    BuildConfig.BASE_URL.toHttpUrlOrNull()?.host,
    "dr-badawi-abdalsalam.com",
)

/** Server timestamps are ISO 8601; older rows may use "YYYY-MM-DD HH:MM:SS" (UTC). */
fun parseServerTime(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { Instant.parse(value.trim().replace(' ', 'T').let { if (it.endsWith("Z")) it else it + "Z" }) }.getOrNull()
}

fun localDate(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()

fun formatClock(instant: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)

fun formatListTime(instant: Instant, today: LocalDate = LocalDate.now()): String {
    val date = localDate(instant)
    return when {
        date == today -> formatClock(instant)
        date.year == today.year -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
        else -> DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(instant)
    }
}

fun formatDay(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)
