package app.localcart.ui.orders

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.chat.ChatEvent
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.OrderSummaryDto
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.domain.Payment
import app.localcart.ui.components.ChoiceChip
import app.localcart.ui.components.EmptyState
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.StatusTag
import app.localcart.ui.components.Thumb
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class OrdersState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val scope: String = "active",
    val orders: List<OrderSummaryDto> = emptyList(),
)

class OrdersViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(OrdersState(scope = saved["scope"] ?: "active"))
    val state = _state.asStateFlow()
    val runner = OrderActionRunner(viewModelScope, container.orders)

    init {
        load()
        viewModelScope.launch {
            container.chat.events.collect { if (it is ChatEvent.Metadata || it is ChatEvent.Received) load(quiet = true) }
        }
    }

    fun setScope(scope: String) {
        saved["scope"] = scope
        _state.update { it.copy(scope = scope, orders = emptyList()) }
        load()
    }

    fun refresh() = load(refreshing = true)

    fun load(quiet: Boolean = false, refreshing: Boolean = false) {
        if (!quiet) _state.update { it.copy(loading = it.orders.isEmpty(), refreshing = refreshing, error = null) }
        viewModelScope.launch {
            try {
                val list = container.orders.list(_state.value.scope)
                _state.update { it.copy(loading = false, refreshing = false, orders = list) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = if (quiet) it.error else e.message) }
            }
        }
    }

    fun reorder(orderId: String, onCreated: (String) -> Unit) = runner.run("reorder-$orderId") {
        val order = reorder(orderId)
        onCreated(order.shop.nexconnId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(container: AppContainer, nav: Nav) {
    val vm: OrdersViewModel = viewModel { OrdersViewModel(container, createSavedStateHandle()) }
    val state by vm.state.collectAsState()
    val busy by vm.runner.busy.collectAsState()
    val error by vm.runner.error.collectAsState()
    val isShop = container.session.currentUser?.isShopkeeper == true
    LaunchedEffect(Unit) { vm.load(quiet = true) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading(title = "Orders")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Active", selected = state.scope == "active", onClick = { vm.setScope("active") })
            ChoiceChip("Past", selected = state.scope == "past", onClick = { vm.setScope("past") })
        }
        error?.let { InlineNotice(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp), action = "Dismiss", onAction = vm.runner::clearError) }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error!!, onRetry = { vm.load() })
                state.orders.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            title = if (state.scope == "active") "No orders in progress" else "No past orders yet",
                            body = when {
                                isShop -> "When a customer sends you a cart, it shows up here and in your chats."
                                state.scope == "active" -> "Open a shop, tap Chat to order and send your list."
                                else -> "Delivered and cancelled orders appear here, ready to reorder."
                            },
                            icon = Icons.Outlined.ReceiptLong,
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.orders, key = { it.id }) { order ->
                        OrderRow(
                            order,
                            isShop = isShop,
                            onClick = { nav.order(order.id) },
                            reordering = busy == "reorder-${order.id}",
                            onReorder = if (!isShop && order.status in setOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
                                { vm.reorder(order.id) { peer -> nav.chat(peer) } }
                            } else null,
                        )
                        HairlineDivider(Modifier.padding(start = 84.dp))
                    }
                }
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("en-IN"))

fun formatTimestamp(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)
}.getOrDefault("")

@Composable
private fun OrderRow(order: OrderSummaryDto, isShop: Boolean, onClick: () -> Unit, reordering: Boolean, onReorder: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Thumb(if (isShop) null else order.shop.photoUrl, if (isShop) order.customer.name else order.shop.name, size = 48.dp, round = isShop)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isShop) order.customer.name else order.shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Money.format(order.totalPaise), style = LocalFigures.current.medium)
            }
            Text("#${order.code}, ${order.itemCount} items, ${formatTimestamp(order.createdAt)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusTag(OrderStatus.label(order.status), statusTone(order.status))
                if (order.paymentMethod != null && order.status != OrderStatus.CANCELLED) {
                    Text(Payment.label(order.paymentMethod, order.paymentStatus), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onReorder != null) {
                QuietButton(if (reordering) "Creating cart" else "Reorder", onClick = onReorder, enabled = !reordering)
            }
        }
    }
}
