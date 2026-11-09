package com.digitronics.chat.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.digitronics.chat.AppContainer
import com.digitronics.chat.BuildConfig
import com.digitronics.chat.R
import com.digitronics.chat.diag.Diagnostics
import com.digitronics.chat.diag.DiagnosticsInfo

@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val registered by container.prefs.pushRegistered.collectAsState(initial = false)
    val realtime by container.realtime.status.collectAsState()
    val update by container.updates.state.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val summary = Diagnostics.summary(
        DiagnosticsInfo(
            appName = stringResource(R.string.app_display_name),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildCommit = BuildConfig.GIT_COMMIT,
            buildType = BuildConfig.BUILD_TYPE,
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL.orEmpty(),
            signedIn = container.session.account != null,
            pushState = notificationStatus(context, registered).name.lowercase(),
            notificationPermission = if (notificationsAllowed(context)) "granted" else "not granted",
            realtimeState = realtime.name.lowercase(),
            updateState = update::class.simpleName.orEmpty(),
            updateCheckingConfigured = container.updates.isConfigured,
        ),
    )
    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.diagnostics_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.diagnostics_explainer), style = MaterialTheme.typography.bodySmall)
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            Button(onClick = {
                copy(context, summary)
                copied = true
            }) { Text(stringResource(R.string.diagnostics_copy)) }
            if (copied) Text(stringResource(R.string.diagnostics_copied), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Digitronics Chat diagnostics", text))
}
