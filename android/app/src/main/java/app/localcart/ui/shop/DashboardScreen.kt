package app.localcart.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.chat.ChatEvent
import app.localcart.chat.Conversation
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.DashboardDto
import app.localcart.domain.Hours
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.ui.components.CountBadge
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.SectionLabel
import app.localcart.ui.components.Sheet
import app.localcart.ui.components.Thumb
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val data: DashboardDto? = null,
    val unreadChats: List<Conversation> = emptyList(),
    val toggling: Boolean = false,
    val notice: String? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    init {
        load()
        // New carts, bills and status changes arrive as chat events: refresh the numbers live.
        viewModelScope.launch {
            container.chat.events.collect { event ->
                if (event is ChatEvent.Received || event is ChatEvent.Metadata) load(quiet = true)
            }
        }
    }

    fun refresh() = load(refreshing = true)

    fun load(quiet: Boolean = false, refreshing: Boolean = false) {
        if (!quiet) _state.update { it.copy(loading = it.data == null, refreshing = refreshing, error = null) }
        viewModelScope.launch {
            try {
                val data = container.orders.dashboard()
                runCatching { container.peers.refresh() }
                val chats = runCatching { container.chat.conversations() }.getOrDefault(emptyList()).filter { it.unread > 0 }
                _state.update { it.copy(loading = false, refreshing = false, data = data, unreadChats = chats) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = if (it.data == null) e.message else null, notice = if (it.data != null && !quiet) e.message else it.notice) }
            }
        }
    }

    fun setOpen(open: Boolean) {
        _state.update { it.copy(toggling = true, notice = null) }
        viewModelScope.launch {
            try {
                val shop = container.shops.setOpen(open)
                _state.update { s -> s.copy(toggling = false, data = s.data?.copy(shop = shop)) }
            } catch (e: ApiException) {
                _state.update { it.copy(toggling = false, notice = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(container: AppContainer, nav: Nav, onOpenOrders: () -> Unit, onOpenChats: () -> Unit) {
    val vm: DashboardViewModel = viewModel { DashboardViewModel(container) }
    val state by vm.state.collectAsState()
    val peers by container.peers.peers.collectAsState()
    val myShop by container.shops.myShop.collectAsState()
    // Tabs keep their view models alive, so refresh whenever the dashboard is shown again
    // (opening hours, settings edits and orders may have changed meanwhile).
    LaunchedEffect(Unit) { vm.load(quiet = true) }
    val data = state.data
    val shop = myShop ?: data?.shop

    Column(Modifier.fillMaxSize()) {
        ScreenHeading(title = shop?.name ?: "Today", subtitle = shop?.let { Hours.range(it.opensAt, it.closesAt) }, trailing = {
            QuietButton("Edit shop", onClick = nav::shopSettings)
        })
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = { vm.load() })
            data != null && shop != null -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Sheet(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (shop.isOpen) "Taking orders" else "Not taking orders", style = MaterialTheme.typography.titleMedium, color = if (shop.isOpen) Palette.Leaf else Palette.Chilli)
                                    Text(
                                        when {
                                            !shop.isOpen -> "Customers can still chat with you, but can't start carts."
                                            shop.openNow -> "Open now. Customers nearby can order."
                                            else -> "Outside opening hours. Carts open at ${Hours.pretty(shop.opensAt)}."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = shop.isOpen,
                                    onCheckedChange = vm::setOpen,
                                    enabled = !state.toggling,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Leaf, checkedThumbColor = Palette.Paper),
                                )
                            }
                        }
                    }
                    state.notice?.let { item { InlineNotice(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) } }
                    item {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("Today's sales", Money.format(data.todaySalesPaise), "${data.todayDelivered} delivered", Modifier.weight(1.3f))
                            Stat("Orders today", data.todayOrders.toString(), "${data.pendingPayments} payments open", Modifier.weight(1f))
                        }
                    }
                    item { SectionLabel("Orders that need you") }
                    item {
                        Sheet(Modifier.padding(horizontal = 20.dp), padding = PaddingValues(vertical = 4.dp)) {
                            Column {
                                val rows = listOf(
                                    OrderStatus.PLACED to "Carts to bill",
                                    OrderStatus.BILLED to "Bills waiting on customer",
                                    OrderStatus.ACCEPTED to "Accepted, to pack",
                                    OrderStatus.PACKED to "Packed, to send out",
                                    OrderStatus.OUT_FOR_DELIVERY to "Out for delivery",
                                )
                                rows.forEachIndexed { index, (status, label) ->
                                    val count = data.counts[status] ?: 0
                                    Row(
                                        Modifier.fillMaxWidth().clickable(onClick = onOpenOrders).padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (count > 0) Palette.Ink else Palette.InkMuted)
                                        Text(count.toString(), style = LocalFigures.current.medium, color = if (count > 0 && status == OrderStatus.PLACED) Palette.Chilli else Palette.Ink)
                                    }
                                    if (index < rows.lastIndex) HairlineDivider(Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Unread chats", Modifier.weight(1f))
                            QuietButton("All chats", onClick = onOpenChats)
                        }
                    }
                    if (state.unreadChats.isEmpty()) {
                        item {
                            Text(
                                "You're all caught up. New carts and messages appear here as they arrive.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.InkMuted,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    items(state.unreadChats, key = { it.peerId }) { convo ->
                        val peer = peers[convo.peerId]
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.chat(convo.peerId) }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(peer?.photoUrl, peer?.name ?: "Customer", size = 40.dp, round = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer?.name ?: "Customer", style = MaterialTheme.typography.titleSmall)
                                Text(convo.preview, style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            CountBadge(convo.unread)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Sheet(modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(value, style = LocalFigures.current.large, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}
