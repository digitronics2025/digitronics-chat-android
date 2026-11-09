package com.digitronics.chat.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.digitronics.chat.AppContainer
import com.digitronics.chat.R
import com.digitronics.chat.data.db.ConversationEntity
import com.digitronics.chat.push.PushConfig
import com.digitronics.chat.push.PushRegistrationWorker
import com.digitronics.chat.ui.components.Avatar
import com.digitronics.chat.ui.components.formatListTime
import com.digitronics.chat.ui.components.parseServerTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    container: AppContainer,
    viewModel: ChatListViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterRow(ui.filter, viewModel::setFilter)
            NotificationPrompt(container)
            if (ui.loadFailed) {
                Text(
                    stringResource(R.string.chats_offline),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                if (ui.conversations.isEmpty()) {
                    // Scrollable so pull-to-refresh still works on an empty list.
                    LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(loaded = ui.loadedOnce, filter = ui.filter) } }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(ui.conversations, key = { it.id }) { row ->
                            ConversationRow(row, onClick = { onOpen(row.id) })
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: ChatFilter, onSelect: (ChatFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            ChatFilter.ALL to R.string.filter_all,
            ChatFilter.UNREAD to R.string.filter_unread,
            ChatFilter.GROUPS to R.string.filter_groups,
            ChatFilter.BOTS to R.string.filter_bots,
        ).forEach { (filter, label) ->
            FilterChip(selected = selected == filter, onClick = { onSelect(filter) }, label = { Text(stringResource(label)) })
        }
    }
}

/** The in-app explanation that precedes Android 13+'s notification permission dialog. */
@Composable
private fun NotificationPrompt(container: AppContainer) {
    if (!PushConfig.isConfigured || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissed by container.prefs.notificationPromptDismissed.collectAsState(initial = true)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) PushRegistrationWorker.enqueue(context, force = true)
        scope.launch { container.prefs.setNotificationPromptDismissed(true) }
    }
    if (granted || dismissed) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.notif_prompt_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.notif_prompt_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { scope.launch { container.prefs.setNotificationPromptDismissed(true) } }) {
                    Text(stringResource(R.string.notif_prompt_later))
                }
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.notif_prompt_allow))
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(row: ConversationEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title.ifBlank { stringResource(R.string.untitled_conversation) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isBot) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.label_bot), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (row.isGroup) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.label_group), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            val preview = row.lastMessagePreview.orEmpty()
            val sender = row.lastMessageSenderName
            Text(
                if (row.isGroup && sender != null && preview.isNotEmpty()) "$sender: $preview" else preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            parseServerTime(row.lastMessageAt)?.let {
                Text(formatListTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.unreadCount > 0) {
                Spacer(Modifier.height(4.dp))
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(if (row.unreadCount > 99) "99+" else row.unreadCount.toString())
                }
            }
        }
    }
}

@Composable
private fun EmptyState(loaded: Boolean, filter: ChatFilter) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(
                when {
                    !loaded -> R.string.chats_loading
                    filter == ChatFilter.ALL -> R.string.chats_empty
                    else -> R.string.chats_empty_filter
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
