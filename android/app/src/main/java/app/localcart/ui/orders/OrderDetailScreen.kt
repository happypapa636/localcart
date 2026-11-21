package app.localcart.ui.orders

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.chat.ChatEvent
import app.localcart.chat.OrderCard
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.OrderDto
import app.localcart.domain.OrderStatus
import app.localcart.domain.Payment
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.KeyValueRow
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.SectionLabel
import app.localcart.ui.components.Sheet
import app.localcart.ui.components.StatusTag
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrderDetailState(val loading: Boolean = true, val error: String? = null)

class OrderDetailViewModel(private val container: AppContainer, private val orderId: String) : ViewModel() {
    private val _state = MutableStateFlow(OrderDetailState())
    val state = _state.asStateFlow()
    val order = container.orders.orders.map { it[orderId] }
    val runner = OrderActionRunner(viewModelScope, container.orders)

    init {
        load()
        viewModelScope.launch {
            container.chat.events.collect { event ->
                val content = (event as? ChatEvent.Metadata)?.message?.content as? OrderCard
                if (content?.orderId == orderId) load()
            }
        }
    }

    fun load() {
        _state.update { it.copy(loading = container.orders.orders.value[orderId] == null, error = null) }
        viewModelScope.launch {
            try {
                container.orders.refresh(orderId)
                _state.update { OrderDetailState(loading = false) }
            } catch (e: ApiException) {
                _state.update { OrderDetailState(loading = false, error = e.message) }
            }
        }
    }
}

@Composable
fun OrderDetailScreen(container: AppContainer, orderId: String, nav: Nav) {
    val vm: OrderDetailViewModel = viewModel(key = "order-$orderId") { OrderDetailViewModel(container, orderId) }
    val state by vm.state.collectAsState()
    val order by vm.order.collectAsState(initial = container.orders.orders.value[orderId])
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding()) {
        AppTopBar(
            title = order?.let { "Order #${it.code}" } ?: "Order",
            subtitle = order?.let { if (it.isCustomerView) it.shop.name else it.customer.name },
            onBack = nav::back,
            actions = {
                val o = order
                if (o != null) {
                    val phone = if (o.isCustomerView) o.shop.phone else o.customer.phone
                    if (phone != null) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+91$phone"))) }) {
                            Icon(Icons.Outlined.Call, contentDescription = "Call")
                        }
                    }
                    IconButton(onClick = { nav.chat(if (o.isCustomerView) o.shop.nexconnId else o.customer.nexconnId) }) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Open chat")
                    }
                }
            },
        )
        val o = order
        when {
            o == null && state.loading -> LoadingState()
            o == null -> ErrorState(state.error ?: "Order not found.", onRetry = vm::load)
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusTag(OrderStatus.label(o.status), statusTone(o.status))
                            Text("Placed ${formatTimestamp(o.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (o.status in OrderStatus.fulfilment) {
                        item { StatusTrack(o.status, Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) }
                    }
                    if (o.status == OrderStatus.CANCELLED) {
                        item {
                            InlineNotice(
                                "Cancelled by ${if (o.cancelledBy == "shop") "the shop" else "the customer"}: ${o.cancelReason ?: "no reason given"}",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item { SectionLabel("Items") }
                    item {
                        Sheet(Modifier.padding(horizontal = 20.dp)) {
                            Column {
                                Ledger(o.items.map { LedgerLine(it.name, it.quantity, it.unit, it.unitPricePaise, it.lineTotalPaise, it.available, it.catalogItemId != null) })
                                if (o.deliveryFeePaise > 0) KeyValueRow("Delivery", app.localcart.domain.Money.format(o.deliveryFeePaise))
                                TotalRow(if (o.status in setOf(OrderStatus.DRAFT, OrderStatus.PLACED)) "Estimated total" else "Total", o.totalPaise)
                                if (o.unpricedCount > 0 && o.status in setOf(OrderStatus.DRAFT, OrderStatus.PLACED)) {
                                    Text("${o.unpricedCount} item(s) will be priced by the shop on the bill.", style = MaterialTheme.typography.bodySmall)
                                }
                                o.billNote?.let { Text("Note from shop: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                            }
                        }
                    }
                    item { SectionLabel("Details") }
                    item {
                        Sheet(Modifier.padding(horizontal = 20.dp)) {
                            Column {
                                KeyValueRow("Payment", Payment.label(o.paymentMethod, o.paymentStatus))
                                if (!o.isCustomerView) {
                                    KeyValueRow("Customer", o.customer.name)
                                    o.customer.phone?.let { KeyValueRow("Phone", "+91 $it") }
                                }
                                val address = o.delivery?.address ?: o.customer.addressText
                                address?.let { KeyValueRow("Deliver to", it) }
                                o.sourceText?.let { KeyValueRow(if (o.source == "voice") "Voice note said" else "Ordered as", it) }
                                o.rating?.let { KeyValueRow("Rating", "$it out of 5${o.ratingComment?.let { c -> ", \"$c\"" } ?: ""}") }
                            }
                        }
                    }
                    if (o.delivery != null) {
                        item {
                            SecondaryButton(
                                "Open delivery location in maps",
                                icon = Icons.Outlined.Place,
                                onClick = {
                                    val d = o.delivery
                                    val uri = Uri.parse("geo:${d.lat},${d.lng}?q=${d.lat},${d.lng}(${Uri.encode(o.customer.name)})")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                OrderActionsBar(container, o, vm.runner, nav)
            }
        }
    }
}

/** The actions available to the viewer for an order's current state, pinned at the bottom of the screen. */
@Composable
fun OrderActionsBar(container: AppContainer, order: OrderDto, runner: OrderActionRunner, nav: Nav) {
    val context = LocalContext.current
    val busy by runner.busy.collectAsState()
    val error by runner.error.collectAsState()
    var cancelling by rememberSaveable { mutableStateOf(false) }
    var rating by rememberSaveable { mutableStateOf(false) }
    var paying by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var noUpiApp by rememberSaveable { mutableStateOf(false) }
    val id = order.id
    val customer = order.isCustomerView

    Column(Modifier.fillMaxWidth().background(Palette.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        error?.let {
            InlineNotice(it, action = "Dismiss", onAction = runner::clearError)
            Spacer(Modifier.height(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (customer) {
                when (order.status) {
                    OrderStatus.DRAFT -> {
                        PrimaryButton("Send cart to shop", onClick = { runner.run("confirm") { confirm(id) } }, loading = busy == "confirm", modifier = Modifier.fillMaxWidth())
                        SecondaryButton("Edit cart", onClick = { editing = true }, modifier = Modifier.fillMaxWidth())
                    }
                    OrderStatus.PLACED -> SecondaryButton("Edit cart", onClick = { editing = true }, modifier = Modifier.fillMaxWidth())
                    OrderStatus.BILLED -> PrimaryButton("Accept bill", onClick = { paying = true }, loading = busy == "accept", modifier = Modifier.fillMaxWidth())
                    OrderStatus.DELIVERED -> if (order.rating == null) PrimaryButton("Rate ${order.shop.name}", onClick = { rating = true }, modifier = Modifier.fillMaxWidth())
                }
                if (order.paymentMethod == "upi" && order.paymentStatus == "pending" && order.status != OrderStatus.CANCELLED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Pay with UPI", onClick = {
                            val link = order.upiLink
                            if (link == null || !launchUpi(context, link)) noUpiApp = true
                        }, modifier = Modifier.weight(1f))
                        PrimaryButton("I've paid", onClick = { runner.run("paid") { payment(id, "marked_paid") } }, loading = busy == "paid", modifier = Modifier.weight(1f))
                    }
                }
                if (order.status == OrderStatus.DELIVERED || order.status == OrderStatus.CANCELLED) {
                    SecondaryButton("Reorder these items", onClick = { runner.run("reorder", onSuccess = { nav.chat(order.shop.nexconnId) }) { reorder(id) } }, modifier = Modifier.fillMaxWidth())
                }
                if (OrderStatus.customerCanCancel(order.status)) {
                    SecondaryButton(if (order.status == OrderStatus.DRAFT) "Discard cart" else "Cancel order", onClick = { cancelling = true }, color = Palette.Chilli, modifier = Modifier.fillMaxWidth())
                }
            } else {
                when (order.status) {
                    OrderStatus.PLACED -> PrimaryButton("Prepare bill", onClick = { nav.bill(id) }, modifier = Modifier.fillMaxWidth())
                    OrderStatus.DRAFT -> Text("The customer is still editing this cart.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                    OrderStatus.BILLED -> Text("Waiting for the customer to accept your bill.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                }
                OrderStatus.nextActionLabel(order.status)?.let { label ->
                    val next = OrderStatus.next(order.status)!!
                    PrimaryButton(label, onClick = { runner.run("advance") { advance(id, next) } }, loading = busy == "advance", modifier = Modifier.fillMaxWidth())
                }
                val canConfirmPayment = order.paymentMethod != null && order.paymentStatus != "confirmed" && order.status != OrderStatus.CANCELLED &&
                    (order.paymentStatus == "marked_paid" || order.paymentMethod == "cod" || order.status == OrderStatus.DELIVERED)
                if (canConfirmPayment) {
                    SecondaryButton(
                        if (order.paymentMethod == "cod") "Cash received" else "Confirm UPI payment received",
                        onClick = { runner.run("payment") { payment(id, "confirmed") } },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Leaf,
                    )
                }
                if (OrderStatus.shopCanCancel(order.status)) {
                    SecondaryButton("Cancel order", onClick = { cancelling = true }, color = Palette.Chilli, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (cancelling) {
        CancelDialog(asShop = !customer, onDismiss = { cancelling = false }) { reason ->
            cancelling = false
            runner.run("cancel") { cancel(id, reason) }
        }
    }
    if (rating) {
        RateDialog(order.shop.name, onDismiss = { rating = false }) { stars, comment ->
            rating = false
            runner.run("rate") { rate(id, stars, comment) }
        }
    }
    if (paying) {
        PaymentDialog(order.totalPaise, order.shop.upiId, onDismiss = { paying = false }) { method ->
            paying = false
            runner.run("accept", onSuccess = {
                if (method == "upi") {
                    val link = container.orders.orders.value[id]?.upiLink
                    if (link == null || !launchUpi(context, link)) noUpiApp = true
                }
            }) { accept(id, method) }
        }
    }
    if (noUpiApp && order.shop.upiId != null) {
        NoUpiAppDialog(order.shop.upiId, order.totalPaise, context) { noUpiApp = false }
    }
    if (editing) {
        CartEditSheet(order, container.shops, saving = busy == "edit", error = error, onDismiss = { editing = false }) { lines ->
            runner.run("edit", onSuccess = { editing = false }) { editCart(id, lines) }
        }
    }
}
