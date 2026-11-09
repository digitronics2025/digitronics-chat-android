package com.digitronics.chat.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitronics.chat.AppContainer
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.db.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChatFilter { ALL, UNREAD, GROUPS, BOTS }

data class ChatListUi(
    val conversations: List<ConversationEntity> = emptyList(),
    val filter: ChatFilter = ChatFilter.ALL,
    val refreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val loadedOnce: Boolean = false,
)

class ChatListViewModel(private val container: AppContainer, private val account: Account) : ViewModel() {
    private val filter = MutableStateFlow(ChatFilter.ALL)
    private val refreshing = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private val loadedOnce = MutableStateFlow(false)

    val ui: StateFlow<ChatListUi> = combine(
        container.chat.conversations(account),
        filter,
        refreshing,
        failed,
        loadedOnce,
    ) { rows, f, r, fail, loaded ->
        ChatListUi(rows.filter { it.matches(f) }, f, r, fail, loaded || rows.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUi())

    init {
        refresh()
    }

    fun setFilter(value: ChatFilter) {
        filter.value = value
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            failed.value = runCatching { container.chat.refreshConversations(account) }.isFailure
            loadedOnce.value = true
            refreshing.value = false
        }
    }

    private fun ConversationEntity.matches(f: ChatFilter) = when (f) {
        ChatFilter.ALL -> true
        ChatFilter.UNREAD -> unreadCount > 0
        ChatFilter.GROUPS -> isGroup
        ChatFilter.BOTS -> isBot
    }
}
