package app.localcart.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.localcart.chat.BillCardMessage
import app.localcart.chat.CardLine
import app.localcart.chat.CardState
import app.localcart.chat.CartCardMessage
import app.localcart.chat.StatusCardMessage
import app.localcart.data.api.OrderDto
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.domain.Payment
import app.localcart.ui.components.KeyValueRow
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.StatusTag
import app.localcart.ui.components.Tone
import app.localcart.ui.orders.Ledger
import app.localcart.ui.orders.LedgerLine
import app.localcart.ui.orders.StatusTrack
import app.localcart.ui.orders.TotalRow
import app.localcart.ui.orders.statusTone
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette

/** Callbacks a card can trigger; the chat screen wires them to the shared order actions. */
class CardActions(
    val busy: String?,
    val openOrder: (String) -> Unit,
    val confirm: (String) -> Unit,
    val edit: (OrderDto) -> Unit,
    val discard: (OrderDto) -> Unit,
    val prepareBill: (String) -> Unit,
    val accept: (OrderDto) -> Unit,
    val decline: (OrderDto) -> Unit,
    val payUpi: (OrderDto) -> Unit,
    val markPaid: (String) -> Unit,
    val confirmPayment: (String) -> Unit,
    val advance: (String, String) -> Unit,
    val cancel: (OrderDto) -> Unit,
    val rate: (OrderDto) -> Unit,
)

private fun CardLine.toLedger() = LedgerLine(name, quantity, unit, if (isPriced) unitPricePaise else null, lineTotalPaise, available, matched)

/**
 * The slip: a white sheet with a hairline border, the order code on top, ledger lines and a total under
 * a dashed tear rule. Cart, Bill and Order status cards all share it so they read as one document.
 */
@Composable
private fun Slip(
    title: String,
    code: String,
    statusText: String,
    tone: Tone,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Palette.Sheet,
        border = BorderStroke(1.dp, Palette.RuleStrong),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("#$code", style = LocalFigures.current.small, color = Palette.InkMuted)
                }
                StatusTag(statusText, tone)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun CartCard(card: CartCardMessage, meta: CardState, order: OrderDto?, viewerIsCustomer: Boolean, actions: CardActions) {
    val status = order?.status ?: meta.status ?: OrderStatus.DRAFT
    val useOrderItems = order != null && order.cartRev > card.rev && status in setOf(OrderStatus.DRAFT, OrderStatus.PLACED)
    val lines = if (useOrderItems) {
        order!!.items.map { LedgerLine(it.name, it.quantity, it.unit, it.unitPricePaise, it.lineTotalPaise, it.available, it.catalogItemId != null) }
    } else card.items.map { it.toLedger() }
    val subtotal = if (useOrderItems) order!!.subtotalPaise else card.subtotalPaise
    val unpriced = lines.count { it.available && (it.unitPricePaise ?: -1) < 0 }
    val statusText = when (status) {
        OrderStatus.DRAFT -> "Draft"
        OrderStatus.PLACED -> "Sent to shop"
        OrderStatus.CANCELLED -> "Cancelled"
        else -> "Billed"
    }
    Slip("Cart", card.code, statusText, if (status == OrderStatus.CANCELLED) Tone.Negative else if (status == OrderStatus.DRAFT) Tone.Neutral else Tone.Positive, { actions.openOrder(card.orderId) }) {
        if (card.sourceText.isNotBlank()) {
            Text(
                (if (card.source == "voice") "From a voice note: " else if (card.source == "reorder") "" else "From: ") + "“${card.sourceText}”",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Ledger(lines)
        TotalRow("Estimated total", subtotal, large = false)
        if (unpriced > 0) Text("$unpriced item(s) for the shop to price", style = MaterialTheme.typography.bodySmall)
        if (status == OrderStatus.CANCELLED) {
            Text(meta.reason ?: order?.cancelReason ?: "Cancelled", style = MaterialTheme.typography.bodySmall, color = Palette.Chilli)
        }
        val o = order ?: return@Slip
        Spacer(Modifier.height(10.dp))
        if (viewerIsCustomer) {
            when (status) {
                OrderStatus.DRAFT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryButton("Send to shop", onClick = { actions.confirm(o.id) }, loading = actions.busy == "confirm-${o.id}", modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Edit", onClick = { actions.edit(o) }, modifier = Modifier.weight(1f))
                        SecondaryButton("Discard", onClick = { actions.discard(o) }, color = Palette.Chilli, modifier = Modifier.weight(1f))
                    }
                }
                OrderStatus.PLACED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Waiting for the shop's bill", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    QuietButton("Edit", onClick = { actions.edit(o) })
                }
            }
        } else {
            when (status) {
                OrderStatus.DRAFT -> Text("The customer is still checking this cart.", style = MaterialTheme.typography.bodySmall)
                OrderStatus.PLACED -> PrimaryButton("Prepare bill", onClick = { actions.prepareBill(o.id) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun BillCard(card: BillCardMessage, meta: CardState, order: OrderDto?, viewerIsCustomer: Boolean, actions: CardActions) {
    val status = order?.status ?: meta.status ?: OrderStatus.BILLED
    val statusText = when (status) {
        OrderStatus.BILLED -> if (viewerIsCustomer) "Review" else "Sent"
        OrderStatus.CANCELLED -> "Cancelled"
        else -> "Accepted"
    }
    Slip("Bill", card.code, statusText, if (status == OrderStatus.CANCELLED) Tone.Negative else if (status == OrderStatus.BILLED) Tone.Strong else Tone.Positive, { actions.openOrder(card.orderId) }) {
        Ledger(card.items.map { it.toLedger() })
        if (card.deliveryFeePaise > 0) KeyValueRow("Delivery", Money.format(card.deliveryFeePaise))
        TotalRow("Total", card.totalPaise)
        if (card.note.isNotBlank()) Text("Note: ${card.note}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        if (status == OrderStatus.CANCELLED) {
            Text(meta.reason ?: order?.cancelReason ?: "Cancelled", style = MaterialTheme.typography.bodySmall, color = Palette.Chilli)
        }
        val o = order ?: return@Slip
        if (status == OrderStatus.BILLED) {
            Spacer(Modifier.height(10.dp))
            if (viewerIsCustomer) {
                PrimaryButton("Accept and pay", onClick = { actions.accept(o) }, loading = actions.busy == "accept-${o.id}", modifier = Modifier.fillMaxWidth())
                QuietButton("Decline bill", onClick = { actions.decline(o) }, color = Palette.Chilli)
            } else {
                Text("Waiting for the customer to accept.", style = MaterialTheme.typography.bodySmall)
            }
        } else if (status != OrderStatus.CANCELLED) {
            Text(Payment.label(o.paymentMethod, o.paymentStatus), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun StatusCard(card: StatusCardMessage, meta: CardState, order: OrderDto?, viewerIsCustomer: Boolean, actions: CardActions) {
    val status = order?.status ?: meta.status ?: OrderStatus.ACCEPTED
    val payment = order?.paymentStatus ?: meta.payment ?: "pending"
    val method = order?.paymentMethod ?: card.paymentMethod.ifBlank { null }
    Slip("Order ${OrderStatus.label(status).lowercase()}", card.code, OrderStatus.label(status), statusTone(status), { actions.openOrder(card.orderId) }) {
        if (status == OrderStatus.CANCELLED) {
            Text("Cancelled: ${meta.reason ?: order?.cancelReason ?: "no reason given"}", style = MaterialTheme.typography.bodyMedium, color = Palette.Chilli)
        } else {
            StatusTrack(status)
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Total", Money.format(card.totalPaise))
        KeyValueRow("Payment", Payment.label(method, payment), valueColor = if (payment == "confirmed") Palette.Leaf else Palette.Ink)
        val rating = order?.rating ?: meta.rating
        if (rating != null) KeyValueRow("Rating", "$rating out of 5")
        val o = order ?: return@Slip
        val buttons = mutableListOf<@Composable () -> Unit>()
        if (viewerIsCustomer) {
            if (method == "upi" && payment == "pending" && status != OrderStatus.CANCELLED) {
                buttons += {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Pay with UPI", onClick = { actions.payUpi(o) }, modifier = Modifier.weight(1f))
                        PrimaryButton("I've paid", onClick = { actions.markPaid(o.id) }, loading = actions.busy == "paid-${o.id}", modifier = Modifier.weight(1f))
                    }
                }
            }
            if (status == OrderStatus.DELIVERED && rating == null) {
                buttons += { PrimaryButton("Rate ${o.shop.name}", onClick = { actions.rate(o) }, modifier = Modifier.fillMaxWidth()) }
            }
            if (OrderStatus.customerCanCancel(status)) {
                buttons += { QuietButton("Cancel order", onClick = { actions.cancel(o) }, color = Palette.Chilli) }
            }
        } else {
            OrderStatus.nextActionLabel(status)?.let { label ->
                val next = OrderStatus.next(status)!!
                buttons += { PrimaryButton(label, onClick = { actions.advance(o.id, next) }, loading = actions.busy == "advance-${o.id}", modifier = Modifier.fillMaxWidth()) }
            }
            val canConfirm = method != null && payment != "confirmed" && status != OrderStatus.CANCELLED &&
                (payment == "marked_paid" || method == "cod" || status == OrderStatus.DELIVERED)
            if (canConfirm) {
                buttons += {
                    SecondaryButton(
                        if (method == "cod") "Cash received" else "Confirm UPI received",
                        onClick = { actions.confirmPayment(o.id) },
                        color = Palette.Leaf,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (OrderStatus.shopCanCancel(status)) {
                buttons += { QuietButton("Cancel order", onClick = { actions.cancel(o) }, color = Palette.Chilli) }
            }
        }
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons.forEach { it() } }
        }
    }
}
