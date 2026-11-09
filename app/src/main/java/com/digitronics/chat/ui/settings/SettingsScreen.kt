package com.digitronics.chat.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.digitronics.chat.AppContainer
import com.digitronics.chat.R
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.ThemeMode
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.push.PushConfig
import com.digitronics.chat.push.PushRegistrationWorker
import kotlinx.coroutines.launch
import java.io.IOException

enum class NotificationStatus { UNAVAILABLE, BLOCKED, REGISTERING, ON }

fun notificationStatus(context: Context, registered: Boolean): NotificationStatus = when {
    !PushConfig.isConfigured -> NotificationStatus.UNAVAILABLE
    !notificationsAllowed(context) -> NotificationStatus.BLOCKED
    registered -> NotificationStatus.ON
    else -> NotificationStatus.REGISTERING
}

fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

@Composable
fun SettingsScreen(
    container: AppContainer,
    account: Account,
    onBack: () -> Unit,
    onUpdates: () -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by container.prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val registered by container.prefs.pushRegistered.collectAsState(initial = false)
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }
    val status = remember(registered, refreshTick) { notificationStatus(context, registered) }
    var testResult by remember { mutableStateOf<Int?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshTick++
        if (granted) PushRegistrationWorker.enqueue(context, force = true)
    }

    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // ── Account ─────────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_account))
            InfoRow(stringResource(R.string.settings_name), account.fullName)
            InfoRow(stringResource(R.string.settings_email), account.email)
            InfoRow(stringResource(R.string.settings_workspace), "${account.workspaceName} (${account.role})")
            Text(
                stringResource(R.string.settings_signout_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_signout))
            }
            HorizontalDivider()

            // ── Notifications ───────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_notifications))
            Text(
                stringResource(
                    when (status) {
                        NotificationStatus.UNAVAILABLE -> R.string.notif_status_unavailable
                        NotificationStatus.BLOCKED -> R.string.notif_status_blocked
                        NotificationStatus.REGISTERING -> R.string.notif_status_registering
                        NotificationStatus.ON -> R.string.notif_status_on
                    },
                ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (status != NotificationStatus.UNAVAILABLE) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status == NotificationStatus.BLOCKED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.notif_turn_on))
                        }
                    }
                    TextButton(onClick = { openNotificationSettings(context) }) { Text(stringResource(R.string.notif_open_settings)) }
                }
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = try {
                                when (container.mobileApi.testPush()) {
                                    "accepted" -> R.string.push_test_accepted
                                    "token_invalid" -> R.string.push_test_token_invalid
                                    "no_token" -> R.string.push_test_no_token
                                    "not_configured" -> R.string.push_test_not_configured
                                    else -> R.string.push_test_failed
                                }
                            } catch (e: ApiException) {
                                if (e.status == 429) R.string.push_test_rate_limited else R.string.push_test_failed
                            } catch (e: IOException) {
                                R.string.error_offline
                            }
                            testing = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.push_test_button)) }
                testResult?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ── Appearance ──────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_appearance))
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = theme == mode, role = Role.RadioButton, onClick = { scope.launch { container.prefs.setThemeMode(mode) } })
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == mode, onClick = null)
                    Text(stringResource(label), modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                }
            }
            HorizontalDivider()

            // ── More ────────────────────────────────────────────────────────
            NavRow(stringResource(R.string.updates_title), onClick = onUpdates)
            NavRow(stringResource(R.string.about_title), onClick = onAbout)
            NavRow(stringResource(R.string.diagnostics_title), stringResource(R.string.diagnostics_summary), onClick = onDiagnostics)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_signout)) },
            text = { Text(stringResource(R.string.settings_signout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    container.scope.launch { container.session.signOut() }
                }) { Text(stringResource(R.string.settings_signout)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
