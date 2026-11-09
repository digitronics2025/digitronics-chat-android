package com.digitronics.chat.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitronics.chat.AppContainer
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.db.ConversationEntity
import com.digitronics.chat.data.db.MessageEntity
import com.digitronics.chat.data.db.OutgoingMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadUi(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val outgoing: List<OutgoingMessageEntity> = emptyList(),
    val reachedStart: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

class ThreadViewModel(
    private val container: AppContainer,
    val account: Account,
    val conversationId: String,
) : ViewModel() {
    private val loading = MutableStateFlow(true)
    private val failed = MutableStateFlow(false)
    private var loadingOlder = false

    val ui: StateFlow<ThreadUi> = combine(
        container.chat.conversation(account, conversationId),
        container.chat.messages(account, conversationId),
        container.chat.outgoing(account, conversationId),
        container.chat.cursor(account, conversationId),
        combine(loading, failed) { l, f -> l to f },
    ) { conversation, messages, outgoing, cursor, (l, f) ->
        ThreadUi(conversation, messages, outgoing, cursor?.reachedStart == true, l, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUi())

    init {
        sync()
    }

    /** Initial load or delta; then mark the newest message read. */
    fun sync() {
        viewModelScope.launch {
            loading.value = true
            failed.value = runCatching { container.chat.syncThread(account, conversationId) }.isFailure
            loading.value = false
            markRead()
        }
    }

    fun markRead() {
        viewModelScope.launch { runCatching { container.chat.markRead(account, conversationId) } }
    }

    fun loadOlder() {
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            runCatching { container.chat.loadOlder(account, conversationId) }
            loadingOlder = false
        }
    }

    fun send(text: String) {
        viewModelScope.launch { container.chat.send(account, conversationId, text) }
    }

    fun retry(clientMessageId: String) {
        viewModelScope.launch { container.chat.retry(account, clientMessageId) }
    }

    fun discard(clientMessageId: String) {
        viewModelScope.launch { container.chat.discard(account, clientMessageId) }
    }
}
