package com.digitronics.chat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.digitronics.chat.AppContainer
import com.digitronics.chat.BuildConfig
import com.digitronics.chat.R
import com.digitronics.chat.update.RejectReason
import com.digitronics.chat.update.UpdateState
import com.digitronics.chat.ui.components.formatListTime
import com.digitronics.chat.ui.components.openInBrowser
import com.digitronics.chat.ui.components.openTrustedUrl
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun UpdatesScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.updates.state.collectAsState()
    val checkedAt by container.updates.lastCheckedAt.collectAsState()

    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.updates_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            InfoRow(stringResource(R.string.about_installed_version), versionLabel())
            InfoRow(stringResource(R.string.about_channel), stringResource(R.string.channel_stable))
            checkedAt?.let { InfoRow(stringResource(R.string.updates_last_checked), formatListTime(Instant.ofEpochMilli(it))) }
            Spacer(Modifier.height(8.dp))
            Text(
                statusText(state),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = if (state is UpdateState.Rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )

            val available = state as? UpdateState.Available
            if (available != null) {
                val manifest = available.manifest
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (available.mandatory) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.updates_latest, manifest.versionName, manifest.versionCode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (available.mandatory) {
                            Text(stringResource(R.string.updates_mandatory), fontWeight = FontWeight.Bold)
                        }
                        if (manifest.securityUpdate) Text(stringResource(R.string.updates_security))
                        Text(stringResource(R.string.updates_size, manifest.artifactBytes / (1024.0 * 1024.0)), style = MaterialTheme.typography.bodySmall)
                        if (manifest.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.updates_whats_new), style = MaterialTheme.typography.labelLarge)
                            Text(manifest.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        // P0: hand the download to the browser; Android's installer does the rest.
                        Button(onClick = { openInBrowser(context, manifest.canonicalDownloadUrl) }) {
                            Text(stringResource(R.string.updates_download))
                        }
                        Text(stringResource(R.string.updates_install_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state !is UpdateState.NotConfigured) {
                OutlinedButton(
                    enabled = state !is UpdateState.Checking,
                    onClick = { scope.launch { container.updates.check() } },
                    modifier = Modifier.padding(16.dp),
                ) { Text(stringResource(R.string.updates_check_now)) }
            }
            TextButton(onClick = { openTrustedUrl(context, BuildConfig.BASE_URL + "/install-android") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.about_download_page))
            }
        }
    }
}

@Composable
fun versionLabel(): String = stringResource(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

@Composable
private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.NotConfigured -> stringResource(R.string.updates_not_configured)
    UpdateState.NeverChecked -> stringResource(R.string.updates_never_checked)
    UpdateState.Checking -> stringResource(R.string.updates_checking)
    UpdateState.UpToDate -> stringResource(R.string.updates_up_to_date)
    UpdateState.NoRelease -> stringResource(R.string.updates_no_release)
    UpdateState.UnsupportedNoOffer -> stringResource(R.string.updates_unsupported)
    is UpdateState.Available -> stringResource(R.string.updates_available)
    UpdateState.Offline -> stringResource(R.string.updates_offline)
    is UpdateState.Failed -> stringResource(R.string.updates_failed)
    is UpdateState.Rejected -> stringResource(
        when (state.reason) {
            RejectReason.EXPIRED -> R.string.updates_rejected_expired
            RejectReason.NOT_NEWER -> R.string.updates_up_to_date
            else -> R.string.updates_rejected
        },
    )
}
