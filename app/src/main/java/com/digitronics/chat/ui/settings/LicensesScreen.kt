package com.digitronics.chat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digitronics.chat.R

/** The libraries actually compiled into this APK (gradle/libs.versions.toml) and their licences. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val names = stringArrayResource(R.array.license_names)
    val licenses = stringArrayResource(R.array.license_texts)
    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.about_licenses), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(names.toList()) { index, name ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(licenses.getOrElse(index) { "" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}
