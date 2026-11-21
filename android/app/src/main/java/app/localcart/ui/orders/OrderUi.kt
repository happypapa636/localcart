package app.localcart.ui.orders

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.localcart.data.api.ApiException
import app.localcart.data.repo.OrderRepository
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.domain.Quantities
import app.localcart.ui.components.ChoiceChip
import app.localcart.ui.components.DashedRule
import app.localcart.ui.components.Field
import app.localcart.ui.components.Tone
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs order actions (shared by chat cards and the order screen) with one busy key and one error. */
class OrderActionRunner(private val scope: CoroutineScope, private val orders: OrderRepository) {
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun run(key: String, onSuccess: () -> Unit = {}, block: suspend OrderRepository.() -> Unit) {
        if (_busy.value != null) return
        _busy.value = key
        _error.value = null
        scope.launch {
            try {
                orders.block()
                onSuccess()
            } catch (e: ApiException) {
                _error.value = e.message
            } finally {
                _busy.value = null
            }
        }
    }
}

fun statusTone(status: String): Tone = when (status) {
    OrderStatus.CANCELLED -> Tone.Negative
    OrderStatus.DELIVERED -> Tone.Positive
    OrderStatus.DRAFT -> Tone.Neutral
    OrderStatus.PLACED, OrderStatus.BILLED -> Tone.Strong
    else -> Tone.Positive
}

/** Opens any UPI app with a prefilled `upi://pay` request. Returns false when the phone has none. */
fun launchUpi(context: Context, link: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
    if (intent.resolveActivity(context.packageManager) == null) return false
    return try {
        context.startActivity(Intent.createChooser(intent, "Pay with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The four delivery steps after a bill is accepted. This really is a sequence, so the steps are numbered;
 * done steps show a tick, the current one is filled leaf green.
 */
@Composable
fun StatusTrack(status: String, modifier: Modifier = Modifier) {
    val steps = OrderStatus.fulfilment
    val current = steps.indexOf(status)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        steps.forEachIndexed { index, step ->
            val done = current >= 0 && index < current
            val active = index == current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (index == 0) Palette.Sheet else if (done || active) Palette.Leaf else Palette.Rule))
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(if (active || done) Palette.Leaf else Palette.Sheet)
                            .border(1.dp, if (active || done) Palette.Leaf else Palette.RuleStrong, CircleShape)
                            .semantics { contentDescription = "${OrderStatus.label(step)}${if (done) ", done" else if (active) ", current" else ""}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Outlined.Check, contentDescription = null, tint = Palette.Paper, modifier = Modifier.size(14.dp))
                        else Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (active) Palette.Paper else Palette.InkMuted)
                    }
                    Box(Modifier.weight(1f).height(2.dp).background(if (index == steps.lastIndex) Palette.Sheet else if (done) Palette.Leaf else Palette.Rule))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    OrderStatus.label(step),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Palette.Ink else Palette.InkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

data class LedgerLine(
    val name: String,
    val quantity: Double,
    val unit: String,
    val unitPricePaise: Int?,
    val lineTotalPaise: Int,
    val available: Boolean,
    val matched: Boolean,
)

/** Bill-style item lines: name and quantity on the left, rupees right-aligned in tabular figures. */
@Composable
fun Ledger(lines: List<LedgerLine>, modifier: Modifier = Modifier) {
    Column(modifier) {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        line.name,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (!line.available) TextDecoration.LineThrough else null,
                        color = if (line.available) Palette.Ink else Palette.InkFaint,
                    )
                    val detail = buildString {
                        append(Quantities.format(line.quantity, line.unit))
                        if (line.unitPricePaise != null && line.unitPricePaise >= 0) append(" at ${Money.format(line.unitPricePaise)}")
                    }
                    Text(detail, style = LocalFigures.current.small.copy(color = Palette.InkMuted))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        !line.available -> "Not available"
                        line.unitPricePaise == null || line.unitPricePaise < 0 -> "Shop to price"
                        else -> Money.format(line.lineTotalPaise)
                    },
                    style = if (line.available && (line.unitPricePaise ?: -1) >= 0) LocalFigures.current.small else MaterialTheme.typography.labelMedium,
                    color = when {
                        !line.available -> Palette.Chilli
                        (line.unitPricePaise ?: -1) < 0 -> Palette.InkMuted
                        else -> Palette.Ink
                    },
                )
            }
        }
    }
}

@Composable
fun TotalRow(label: String, paise: Int, modifier: Modifier = Modifier, large: Boolean = true) {
    Column(modifier) {
        DashedRule()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(Money.format(paise), style = if (large) LocalFigures.current.large else LocalFigures.current.medium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CancelDialog(asShop: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val reasons = if (asShop) {
        listOf("Items not available", "Can't deliver right now", "Shop is closing", "Customer not reachable")
    } else {
        listOf("Ordered by mistake", "Taking too long", "Prices changed", "Found it elsewhere")
    }
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel this order?") },
        text = {
            Column {
                Text(
                    if (asShop) "The customer sees your reason on the order card." else "The shop sees your reason on the order card.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasons.forEach { r -> ChoiceChip(r, selected = reason == r, onClick = { reason = r }) }
                }
                Spacer(Modifier.height(10.dp))
                Field(reason, { reason = it }, label = "Reason")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.trim().length >= 2) { Text("Cancel order", color = Palette.Chilli) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep order") } },
    )
}

@Composable
fun RateDialog(shopName: String, onDismiss: () -> Unit, onSubmit: (Int, String?) -> Unit) {
    var stars by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rate $shopName") },
        text = {
            Column {
                Row {
                    (1..5).forEach { i ->
                        IconButton(onClick = { stars = i }) {
                            Icon(
                                if (i <= stars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = "$i star${if (i > 1) "s" else ""}",
                                tint = if (i <= stars) Palette.Leaf else Palette.InkFaint,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field(comment, { comment = it }, label = "Anything to add? (optional)", singleLine = false, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(stars, comment.ifBlank { null }) }, enabled = stars > 0) { Text("Submit rating") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

/** Accepting a bill: cash on delivery, or UPI straight to the shop's UPI ID. */
@Composable
fun PaymentDialog(totalPaise: Int, upiId: String?, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var method by rememberSaveable { mutableStateOf(if (upiId != null) "upi" else "cod") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accept bill of ${Money.format(totalPaise)}") },
        text = {
            Column {
                PaymentOption("Pay with UPI", if (upiId != null) "Opens your UPI app to pay $upiId" else "This shop has not added a UPI ID", method == "upi", enabled = upiId != null) { method = "upi" }
                PaymentOption("Cash on delivery", "Pay when the order arrives", method == "cod", enabled = true) { method = "cod" }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(method) }) { Text(if (method == "upi") "Accept and pay" else "Accept order") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun PaymentOption(title: String, body: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Palette.Leaf))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Palette.Ink else Palette.InkFaint)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Shown when no UPI app is installed: the details to pay manually from another phone or app. */
@Composable
fun NoUpiAppDialog(upiId: String, totalPaise: Int, context: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No UPI app on this phone") },
        text = {
            Text("Pay ${Money.format(totalPaise)} to $upiId from any UPI app, then tap \"I've paid\" on the order card.")
        },
        confirmButton = { TextButton(onClick = { copyToClipboard(context, "UPI ID", upiId); onDismiss() }) { Text("Copy UPI ID") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
