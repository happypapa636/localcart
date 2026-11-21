package app.localcart.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.AppJson
import app.localcart.data.api.BillLineRequest
import app.localcart.data.api.BillRequest
import app.localcart.data.api.OrderDto
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.domain.Quantities
import app.localcart.domain.Unit as SellUnit
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.Field
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.Sheet
import app.localcart.ui.nav.Nav
import app.localcart.ui.orders.TotalRow
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class BillLine(
    val itemId: String,
    val name: String,
    val unit: String,
    val requested: String?,
    val available: Boolean,
    val quantity: String,
    val price: String,
)

data class BillState(
    val loading: Boolean = true,
    val error: String? = null,
    val order: OrderDto? = null,
    val lines: List<BillLine> = emptyList(),
    val sending: Boolean = false,
    val sendError: String? = null,
)

/** The shop turns a cart into a bill: availability, quantity and price per line, plus delivery fee. */
class BillEditorViewModel(private val container: AppContainer, private val orderId: String, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(BillState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val order = container.orders.refresh(orderId)
                val restored = saved.get<String>("lines")?.let {
                    runCatching { AppJson.decodeFromString(ListSerializer(BillLine.serializer()), it) }.getOrNull()
                }
                val lines = restored ?: order.items.map {
                    BillLine(
                        itemId = it.id,
                        name = it.name,
                        unit = it.unit,
                        requested = it.requestedText,
                        available = it.available,
                        quantity = Quantities.format(it.quantity, it.unit).substringBefore(' '),
                        price = it.unitPricePaise?.let(Money::toInput).orEmpty(),
                    )
                }
                _state.update { it.copy(loading = false, order = order, lines = lines) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun edit(line: BillLine) {
        val lines = _state.value.lines.map { if (it.itemId == line.itemId) line else it }
        saved["lines"] = AppJson.encodeToString(ListSerializer(BillLine.serializer()), lines)
        _state.update { it.copy(lines = lines) }
    }

    fun send(deliveryFee: String, note: String, onSent: () -> Unit) {
        val lines = _state.value.lines
        val bad = lines.firstOrNull { it.available && ((Money.parseRupees(it.price) ?: 0) <= 0 || (it.quantity.toDoubleOrNull() ?: 0.0) <= 0) }
        if (bad != null) {
            _state.update { it.copy(sendError = "Set a quantity and price for ${bad.name}, or mark it unavailable.") }
            return
        }
        if (lines.none { it.available }) {
            _state.update { it.copy(sendError = "Mark at least one item available, or cancel the order instead.") }
            return
        }
        val fee = if (deliveryFee.isBlank()) 0 else Money.parseRupees(deliveryFee)
        if (fee == null) {
            _state.update { it.copy(sendError = "Enter the delivery fee in rupees, or leave it empty.") }
            return
        }
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                container.orders.sendBill(
                    orderId,
                    BillRequest(
                        lines = lines.map {
                            BillLineRequest(it.itemId, it.available, it.quantity.toDoubleOrNull() ?: 1.0, Money.parseRupees(it.price) ?: 0)
                        },
                        deliveryFeePaise = fee,
                        note = note.ifBlank { null },
                    ),
                )
                saved.remove<String>("lines")
                _state.update { it.copy(sending = false) }
                onSent()
            } catch (e: ApiException) {
                _state.update { it.copy(sending = false, sendError = e.message) }
            }
        }
    }
}

@Composable
fun BillEditorScreen(container: AppContainer, orderId: String, nav: Nav) {
    val vm: BillEditorViewModel = viewModel(key = "bill-$orderId") { BillEditorViewModel(container, orderId, createSavedStateHandle()) }
    val state by vm.state.collectAsState()
    var deliveryFee by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val order = state.order

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AppTopBar(title = order?.let { "Bill for #${it.code}" } ?: "Prepare bill", subtitle = order?.customer?.name, onBack = nav::back)
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = vm::load)
            order != null && order.status != OrderStatus.PLACED -> InlineNotice(
                if (order.status == OrderStatus.DRAFT) "The customer hasn't sent this cart yet." else "This cart is already ${OrderStatus.label(order.status).lowercase()}.",
                modifier = Modifier.padding(20.dp),
            )
            order != null -> {
                val subtotal = state.lines.filter { it.available }.sumOf { line ->
                    val p = Money.parseRupees(line.price) ?: 0
                    Quantities.lineTotal(line.quantity.toDoubleOrNull() ?: 0.0, p)
                }
                val fee = Money.parseRupees(deliveryFee) ?: 0
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Check each item. Switch off anything you don't have; prices come from your catalog and can be changed for this bill.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                    }
                    items(state.lines, key = { it.itemId }) { line ->
                        Sheet {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.name, style = MaterialTheme.typography.titleSmall, color = if (line.available) Palette.Ink else Palette.InkFaint)
                                        line.requested?.let { Text("Asked: “$it”", style = MaterialTheme.typography.bodySmall) }
                                    }
                                    Switch(
                                        checked = line.available,
                                        onCheckedChange = { vm.edit(line.copy(available = it)) },
                                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Leaf, checkedThumbColor = Palette.Paper),
                                    )
                                }
                                if (line.available) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Field(line.quantity, { vm.edit(line.copy(quantity = it)) }, label = "Qty (${SellUnit.of(line.unit).label})", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                                        Field(line.price, { vm.edit(line.copy(price = it)) }, label = "Price per ${SellUnit.of(line.unit).label}", prefix = "₹ ", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                                    }
                                    val total = Quantities.lineTotal(line.quantity.toDoubleOrNull() ?: 0.0, Money.parseRupees(line.price) ?: 0)
                                    Text("Line total ${Money.format(total)}", style = LocalFigures.current.small, color = Palette.InkMuted)
                                } else {
                                    Text("Not available. The customer will see it struck off.", style = MaterialTheme.typography.bodySmall, color = Palette.Chilli)
                                }
                            }
                        }
                    }
                    item {
                        Field(deliveryFee, { deliveryFee = it }, label = "Delivery fee (optional)", prefix = "₹ ", keyboardType = KeyboardType.Decimal)
                        Spacer(Modifier.height(4.dp))
                        Field(note, { note = it }, label = "Note for the customer (optional)", placeholder = "Eggs finished, sending tomorrow", singleLine = false, minLines = 2)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    TotalRow("Bill total", subtotal + fee)
                    state.sendError?.let {
                        Spacer(Modifier.height(8.dp))
                        InlineNotice(it)
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Send bill", onClick = { vm.send(deliveryFee, note, nav::back) }, loading = state.sending, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
