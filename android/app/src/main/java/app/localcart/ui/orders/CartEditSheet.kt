package app.localcart.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.localcart.data.api.CartLineRequest
import app.localcart.data.api.CatalogItemDto
import app.localcart.data.api.OrderDto
import app.localcart.data.repo.ShopRepository
import app.localcart.domain.Money
import app.localcart.domain.Quantities
import app.localcart.domain.Unit as SellUnit
import app.localcart.ui.components.Field
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.QuietButton
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette

private data class EditLine(
    val key: String,
    val catalogItemId: String?,
    val name: String,
    val unit: String,
    val quantity: Double,
    val unitPricePaise: Int?,
    val requestedText: String?,
)

/**
 * Customer edits a cart before the shop bills it: change quantities by the unit's step, remove lines,
 * or add items from the shop's catalog. Saving updates the Cart card in the chat for both sides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartEditSheet(
    order: OrderDto,
    shops: ShopRepository,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (List<CartLineRequest>) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lines = remember(order.id, order.cartRev) {
        mutableStateListOf(*order.items.map {
            EditLine(it.id, it.catalogItemId, it.name, it.unit, it.quantity, it.unitPricePaise, it.requestedText)
        }.toTypedArray())
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<CatalogItemDto>?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(adding) {
        if (adding && catalog == null) {
            try {
                catalog = shops.detail(order.shop.id).items.filter { it.inStock }
            } catch (e: Exception) {
                catalogError = e.message ?: "Couldn't load the catalog."
            }
        }
    }
    val total = lines.sumOf { l -> l.unitPricePaise?.let { Quantities.lineTotal(l.quantity, it) } ?: 0 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Paper) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Edit cart #${order.code}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(lines, key = { it.key }) { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                line.unitPricePaise?.let { "${Money.format(it)} per ${SellUnit.of(line.unit).label}" } ?: "Shop will price this",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = {
                            val i = lines.indexOfFirst { it.key == line.key }
                            if (i >= 0) lines[i] = line.copy(quantity = Quantities.decrease(line.quantity, line.unit))
                        }) { Icon(Icons.Outlined.Remove, contentDescription = "Less ${line.name}") }
                        Text(Quantities.format(line.quantity, line.unit), style = LocalFigures.current.small, modifier = Modifier.width(76.dp), maxLines = 1)
                        IconButton(onClick = {
                            val i = lines.indexOfFirst { it.key == line.key }
                            if (i >= 0) lines[i] = line.copy(quantity = Quantities.increase(line.quantity, line.unit))
                        }) { Icon(Icons.Outlined.Add, contentDescription = "More ${line.name}") }
                        IconButton(onClick = { lines.removeAll { it.key == line.key } }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove ${line.name}", tint = Palette.Chilli)
                        }
                    }
                    HairlineDivider()
                }
                if (adding) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Field(search, { search = it }, label = "Search ${order.shop.name}")
                        catalogError?.let { InlineNotice(it) }
                    }
                    val matches = catalog.orEmpty()
                        .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
                        .take(30)
                    items(matches, key = { "add-${it.id}" }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val existing = lines.indexOfFirst { it.catalogItemId == item.id }
                                if (existing >= 0) {
                                    val l = lines[existing]
                                    lines[existing] = l.copy(quantity = Quantities.increase(l.quantity, l.unit))
                                } else {
                                    lines.add(EditLine("new-${item.id}", item.id, item.name, item.unit, Quantities.defaultQuantity(item.unit), item.pricePaise, null))
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${Money.format(item.pricePaise)} / ${SellUnit.of(item.unit).label}", style = LocalFigures.current.small, color = Palette.InkMuted)
                            Icon(Icons.Outlined.Add, contentDescription = "Add ${item.name}", tint = Palette.Leaf, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            if (!adding) QuietButton("Add items from the shop", onClick = { adding = true })
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estimated total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Money.format(total), style = LocalFigures.current.medium)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                InlineNotice(it)
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (lines.isEmpty()) "Add at least one item" else "Save cart",
                enabled = lines.isNotEmpty(),
                loading = saving,
                onClick = {
                    onSave(lines.map { CartLineRequest(it.catalogItemId, it.name, it.unit, it.quantity, it.requestedText) })
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
