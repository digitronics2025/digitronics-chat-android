package com.digitronics.chat.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digitronics.chat.AppContainer
import com.digitronics.chat.R
import com.digitronics.chat.data.db.MessageEntity
import com.digitronics.chat.data.db.OutgoingMessageEntity
import com.digitronics.chat.ui.components.formatClock
import com.digitronics.chat.ui.components.formatDay
import com.digitronics.chat.ui.components.localDate
import com.digitronics.chat.ui.components.parseServerTime
import java.time.LocalDate

/** One row of the reversed thread list (index 0 = newest, at the bottom). */
private sealed interface ThreadItem {
    val key: String

    data class Pending(val row: OutgoingMessageEntity) : ThreadItem {
        override val key = "out:" + row.clientMessageId
    }

    data class Message(val row: MessageEntity, val read: Boolean) : ThreadItem {
        override val key = "msg:" + row.id
    }

    data class Day(val date: LocalDate) : ThreadItem {
        override val key = "day:$date"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(container: AppContainer, viewModel: ThreadViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val conversation = ui.conversation
    val me = viewModel.account.userId

    // While this thread is on screen, its pushes sync instead of ringing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel.conversationId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    container.activeThread.conversationId = viewModel.conversationId
                    container.notifier.cancelConversation(viewModel.conversationId)
                    viewModel.sync()
                }
                Lifecycle.Event.ON_PAUSE -> container.activeThread.conversationId = null
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (container.activeThread.conversationId == viewModel.conversationId) container.activeThread.conversationId = null
        }
    }

    val newestId = ui.messages.firstOrNull()?.id
    LaunchedEffect(newestId) { if (newestId != null) viewModel.markRead() }

    val items = remember(ui.messages, ui.outgoing, conversation?.peerLastReadMessageId) {
        buildItems(ui.messages, ui.outgoing, me, conversation?.peerLastReadMessageId, conversation?.isGroup == true)
    }
    val listState = rememberLazyListState()
    val nearTop by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearTop, ui.messages.size, ui.reachedStart) {
        if (nearTop && !ui.reachedStart && ui.messages.isNotEmpty()) viewModel.loadOlder()
    }
    LaunchedEffect(ui.outgoing.size) { if (ui.outgoing.isNotEmpty()) listState.animateScrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(
                            conversation?.title?.ifBlank { null } ?: stringResource(R.string.untitled_conversation),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when {
                            conversation == null -> null
                            conversation.isBot -> stringResource(R.string.thread_bot_subtitle)
                            conversation.isGroup -> pluralStringResource(R.plurals.thread_members, conversation.memberCount, conversation.memberCount)
                            else -> null
                        }
                        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (ui.loadFailed) {
                Text(
                    stringResource(R.string.thread_offline),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.sync() }.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (items.isEmpty() && ui.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.thread_empty),
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is ThreadItem.Day -> DaySeparator(item.date)
                            is ThreadItem.Pending -> PendingBubble(item.row, onRetry = { viewModel.retry(it) }, onDiscard = { viewModel.discard(it) })
                            is ThreadItem.Message -> MessageRow(item, me, showSender = conversation?.isGroup == true)
                        }
                    }
                }
            }
            when {
                conversation?.isBot == true -> ComposerNote(stringResource(R.string.thread_bot_readonly))
                !viewModel.account.canSend -> ComposerNote(stringResource(R.string.thread_cannot_send))
                else -> Composer(onSend = viewModel::send)
            }
        }
    }
}

private fun buildItems(
    messages: List<MessageEntity>,
    outgoing: List<OutgoingMessageEntity>,
    me: String,
    peerLastReadId: String?,
    isGroup: Boolean,
): List<ThreadItem> {
    val out = mutableListOf<ThreadItem>()
    outgoing.forEach { out += ThreadItem.Pending(it) }
    // Read watermark for 1:1 threads: everything I sent up to the peer's last-read message.
    val watermark = peerLastReadId?.let { id -> messages.firstOrNull { it.id == id }?.createdAt }
    messages.forEachIndexed { index, message ->
        val read = !isGroup && message.senderId == me &&
            (message.readAt != null || (watermark != null && message.createdAt <= watermark))
        out += ThreadItem.Message(message, read)
        val day = parseServerTime(message.createdAt)?.let(::localDate)
        val olderDay = messages.getOrNull(index + 1)?.let { parseServerTime(it.createdAt) }?.let(::localDate)
        if (day != null && day != olderDay) out += ThreadItem.Day(day)
    }
    return out
}

@Composable
private fun DaySeparator(date: LocalDate) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                formatDay(date),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageRow(item: ThreadItem.Message, me: String, showSender: Boolean) {
    val message = item.row
    if (message.messageType == "system") {
        ReportCard(message.systemTitle, message.systemSeverity, message.body, message.systemBlocksJson)
        return
    }
    val mine = message.senderId == me
    val time = parseServerTime(message.createdAt)?.let(::formatClock).orEmpty()
    Bubble(mine = mine) {
        if (showSender && !mine && message.senderName != null) {
            Text(message.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        when {
            message.deletedAt != null -> Text(stringResource(R.string.message_deleted), fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.messageType != "text" -> {
                Text(stringResource(attachmentLabel(message.messageType), message.mediaFileName.orEmpty()), fontStyle = FontStyle.Italic)
                if (message.body.isNotBlank()) Text(message.body)
            }
            else -> Text(message.body)
        }
        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mine && !showSender) {
                Spacer(Modifier.width(4.dp))
                Text(
                    if (item.read) "✓✓" else "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier,
                )
            }
        }
    }
}

private fun attachmentLabel(type: String): Int = when (type) {
    "image" -> R.string.attachment_image
    "video" -> R.string.attachment_video
    "audio" -> R.string.attachment_audio
    "location" -> R.string.attachment_location
    else -> R.string.attachment_document
}

@Composable
private fun PendingBubble(row: OutgoingMessageEntity, onRetry: (String) -> Unit, onDiscard: (String) -> Unit) {
    val failed = row.state == OutgoingMessageEntity.STATE_FAILED
    Bubble(mine = true) {
        Text(row.body)
        if (failed) {
            Row(Modifier.align(Alignment.End)) {
                Text(
                    stringResource(R.string.message_retry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onRetry(row.clientMessageId) }.padding(4.dp),
                )
                Text(
                    stringResource(R.string.message_discard),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onDiscard(row.clientMessageId) }.padding(4.dp),
                )
            }
        } else {
            Text(
                stringResource(R.string.message_sending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun Bubble(mine: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                )
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun ComposerNote(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var draft by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 4096) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.composer_hint)) },
                maxLines = 5,
            )
            IconButton(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
            }
        }
    }
}
