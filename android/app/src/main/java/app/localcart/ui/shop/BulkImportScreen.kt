package app.localcart.ui.shop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.localcart.data.api.ItemRequest
import app.localcart.domain.Money
import app.localcart.domain.Unit as SellUnit
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.Field
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.Sheet
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class DraftRow(val key: Long, val name: String, val unit: String, val price: String)

data class ImportState(val reading: Boolean = false, val saving: Boolean = false, val error: String? = null, val rows: List<DraftRow> = emptyList())

/** Rows survive process death through SavedStateHandle (serialized as JSON). */
class BulkImportViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(ImportState(rows = restore()))
    val state = _state.asStateFlow()

    private fun restore(): List<DraftRow> = saved.get<String>("rows")?.let {
        runCatching { AppJson.decodeFromString(ListSerializer(DraftRow.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun persist(rows: List<DraftRow>) {
        saved["rows"] = AppJson.encodeToString(ListSerializer(DraftRow.serializer()), rows)
        _state.update { it.copy(rows = rows) }
    }

    fun read(text: String) {
        if (text.trim().length < 3) {
            _state.update { it.copy(error = "Type or paste your list first, one product per line with its price.") }
            return
        }
        _state.update { it.copy(reading = true, error = null) }
        viewModelScope.launch {
            try {
                val parsed = container.catalog.parseList(text)
                val start = System.nanoTime()
                persist(_state.value.rows + parsed.mapIndexed { i, item ->
                    DraftRow(start + i, item.name, item.unit, item.pricePaise?.let(Money::toInput).orEmpty())
                })
                _state.update { it.copy(reading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(reading = false, error = e.message) }
            }
        }
    }

    fun edit(row: DraftRow) = persist(_state.value.rows.map { if (it.key == row.key) row else it })
    fun remove(key: Long) = persist(_state.value.rows.filterNot { it.key == key })

    fun save(onDone: () -> Unit) {
        val rows = _state.value.rows
        val invalid = rows.firstOrNull { it.name.isBlank() || (Money.parseRupees(it.price) ?: 0) <= 0 }
        if (invalid != null) {
            _state.update { it.copy(error = "Every item needs a name and a price. Fix \"${invalid.name.ifBlank { "unnamed item" }}\" or remove it.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                container.catalog.addAll(rows.map { ItemRequest(it.name.trim(), it.unit, Money.parseRupees(it.price)!!, true, null) })
                persist(emptyList())
                _state.update { it.copy(saving = false) }
                onDone()
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}

@Composable
fun BulkImportScreen(container: AppContainer, nav: Nav) {
    val vm: BulkImportViewModel = viewModel { BulkImportViewModel(container, createSavedStateHandle()) }
    val state by vm.state.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AppTopBar(title = "Add items from a list", onBack = nav::back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            item {
                Text(
                    "Type or paste your price list in any style, in English or Hindi. LocalCart reads it into items you can check before saving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.InkMuted,
                )
                Spacer(Modifier.height(12.dp))
                Field(
                    text,
                    { text = it },
                    label = "Your list",
                    placeholder = "Aloo 30/kg\nPyaaz 40 kilo\nAmul Taaza 500ml 28\nAnde 7 each",
                    singleLine = false,
                    minLines = 5,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    if (state.reading) "Reading your list" else "Read my list",
                    onClick = { vm.read(text) },
                    enabled = !state.reading,
                    icon = Icons.Outlined.AutoAwesome,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    InlineNotice(it)
                }
                if (state.rows.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Check ${state.rows.size} items", style = MaterialTheme.typography.titleMedium)
                    Text("Fix names, units and prices. Remove anything you don't sell.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.key }) { _, row ->
                DraftRowEditor(row, onChange = vm::edit, onRemove = { vm.remove(row.key) })
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.rows.isNotEmpty()) {
            PrimaryButton(
                "Add ${state.rows.size} items to catalog",
                onClick = { vm.save(nav::back) },
                loading = state.saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DraftRowEditor(row: DraftRow, onChange: (DraftRow) -> Unit, onRemove: () -> Unit) {
    var unitMenu by remember { mutableStateOf(false) }
    Sheet(padding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(row.name, { onChange(row.copy(name = it)) }, label = "Name", modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remove ${row.name}") }
            }
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    row.price,
                    { onChange(row.copy(price = it)) },
                    label = "Price",
                    prefix = "₹ ",
                    keyboardType = KeyboardType.Decimal,
                    error = if ((Money.parseRupees(row.price) ?: 0) <= 0) "Add price" else null,
                    modifier = Modifier.weight(1f),
                )
                Column {
                    SecondaryButton("per ${SellUnit.of(row.unit).label}", onClick = { unitMenu = true })
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        SellUnit.entries.forEach { u ->
                            DropdownMenuItem(text = { Text(u.label) }, onClick = { onChange(row.copy(unit = u.code)); unitMenu = false })
                        }
                    }
                }
            }
        }
    }
}
