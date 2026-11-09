package com.digitronics.chat.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.digitronics.chat.BuildConfig
import com.digitronics.chat.R
import com.digitronics.chat.ui.components.AppLogo
import com.digitronics.chat.ui.components.openTrustedUrl

const val DEVELOPER_URL = "https://dr-badawi-abdalsalam.com/"

@Composable
fun AboutScreen(onBack: () -> Unit, onUpdates: () -> Unit, onLicenses: () -> Unit) {
    val context = LocalContext.current
    val downloadPage = BuildConfig.BASE_URL + "/install-android"
    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppLogo(size = 80.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_display_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            HorizontalDivider()
            InfoRow(stringResource(R.string.about_installed_version), versionLabel())
            InfoRow(stringResource(R.string.about_channel), stringResource(R.string.channel_stable))
            InfoRow(stringResource(R.string.about_build_commit), BuildConfig.GIT_COMMIT)
            InfoRow(stringResource(R.string.about_developer), stringResource(R.string.developer_name), onClick = { openTrustedUrl(context, DEVELOPER_URL) })
            InfoRow(stringResource(R.string.about_developer_website), DEVELOPER_URL, onClick = { openTrustedUrl(context, DEVELOPER_URL) })
            InfoRow(stringResource(R.string.about_brand), stringResource(R.string.brand_name))
            HorizontalDivider()
            NavRow(stringResource(R.string.updates_check_now), onClick = onUpdates)
            NavRow(stringResource(R.string.about_download_page), downloadPage, onClick = { openTrustedUrl(context, downloadPage) })
            val shareTitle = stringResource(R.string.about_share)
            NavRow(shareTitle, stringResource(R.string.about_share_summary), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, downloadPage)
                runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
            })
            NavRow(stringResource(R.string.about_privacy), onClick = { openTrustedUrl(context, BuildConfig.BASE_URL + "/privacy") })
            NavRow(stringResource(R.string.about_terms), onClick = { openTrustedUrl(context, BuildConfig.BASE_URL + "/terms") })
            NavRow(stringResource(R.string.about_licenses), onClick = onLicenses)
            Spacer(Modifier.height(24.dp))
        }
    }
}
