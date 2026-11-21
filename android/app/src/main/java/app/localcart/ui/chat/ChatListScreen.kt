package app.localcart.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.chat.ChatConnection
import app.localcart.chat.ChatEvent
import app.localcart.chat.Conversation
import app.localcart.core.AppContainer
import app.localcart.ui.components.CountBadge
import app.localcart.ui.components.EmptyState
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.Thumb
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ChatListState(val loading: Boolean = true, val refreshing: Boolean = false, val conversations: List<Conversation> = emptyList())

class ChatListViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ChatListState())
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            container.chat.events.collect { if (it is ChatEvent.Received || it is ChatEvent.Sent || it is ChatEvent.Deleted) load() }
        }
        viewModelScope.launch {
            container.chat.connection.collect { if (it == ChatConnection.Connected) load() }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching { container.peers.refresh() }
            // The SDK database opens shortly after connect; retry briefly on a cold start.
            var list = runCatching { container.chat.conversations() }.getOrNull()
            var attempts = 0
            while (list == null && attempts < 6) {
                delay(500)
                list = runCatching { container.chat.conversations() }.getOrNull()
                attempts++
            }
            val peers = container.peers.peers.value
            // Only show chats with people LocalCart knows (the App Key is shared with other apps).
            val visible = list.orEmpty().filter { peers.containsKey(it.peerId) }
            _state.update { ChatListState(loading = false, refreshing = false, conversations = visible) }
            container.chat.refreshUnread()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(container: AppContainer, nav: Nav) {
    val vm: ChatListViewModel = viewModel { ChatListViewModel(container) }
    val state by vm.state.collectAsState()
    val peers by container.peers.peers.collectAsState()
    val isShop = container.session.currentUser?.isShopkeeper == true
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading(title = "Chats")
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState()
                state.conversations.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            title = "No chats yet",
                            body = if (isShop) "Customers who message your shop or send a cart show up here." else "Open a shop and tap Chat to order to start a conversation.",
                            icon = Icons.Outlined.ChatBubbleOutline,
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.conversations, key = { it.peerId }) { convo ->
                        val peer = peers[convo.peerId]
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.chat(convo.peerId) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(peer?.photoUrl, peer?.name ?: "?", size = 48.dp, round = peer?.kind == "customer")
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        peer?.name ?: "Chat",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (convo.unread > 0) FontWeight(700) else FontWeight(600)),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(relativeTime(convo.time), style = MaterialTheme.typography.labelSmall, color = if (convo.unread > 0) Palette.Chilli else Palette.InkFaint)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        (if (convo.lastFromMe) "You: " else "") + convo.preview,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (convo.unread > 0) Palette.Ink else Palette.InkMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    CountBadge(convo.unread)
                                }
                            }
                        }
                        HairlineDivider(Modifier.padding(start = 82.dp))
                    }
                }
            }
        }
    }
}

private val hm = SimpleDateFormat("h:mm a", Locale.forLanguageTag("en-IN"))
private val dm = SimpleDateFormat("d MMM", Locale.forLanguageTag("en-IN"))

fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    val now = Calendar.getInstance()
    return if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
        hm.format(Date(epochMs))
    } else {
        dm.format(Date(epochMs))
    }
}
