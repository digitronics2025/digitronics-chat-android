package com.digitronics.chat.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val info = DiagnosticsInfo(
        appName = "Digitronics Chat", versionName = "1.0.0", versionCode = 7, buildCommit = "abc1234",
        buildType = "release", sdkInt = 35, deviceModel = "Pixel 8", signedIn = true, pushState = "on",
        notificationPermission = "granted", realtimeState = "connected", updateState = "UpToDate",
        updateCheckingConfigured = true,
    )

    @Test
    fun `summary carries versions and states`() {
        val text = Diagnostics.summary(info)
        assertTrue(text.contains("1.0.0 (7)"))
        assertTrue(text.contains("Android SDK: 35"))
        assertTrue(text.contains("Push: on"))
    }

    @Test
    fun `redaction removes tokens, bearer values, emails and long secrets`() {
        val leaked = Diagnostics.summary(
            info.copy(
                deviceModel = "dcm_rt_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG person@example.com",
                pushState = "Bearer eyJhbGciOi " + "x".repeat(60),
            ),
        )
        assertFalse(leaked.contains("dcm_rt_"))
        assertFalse(leaked.contains("person@example.com"))
        assertFalse(leaked.contains("eyJhbGciOi"))
        assertFalse(leaked.contains("x".repeat(60)))
    }
}
