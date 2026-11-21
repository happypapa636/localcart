package app.localcart.ui.shop

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.CatalogItemDto
import app.localcart.data.api.ItemRequest
import app.localcart.domain.Money
import app.localcart.domain.Unit as SellUnit
import app.localcart.media.ImageFiles
import app.localcart.ui.components.ChoiceChip
import app.localcart.ui.components.EmptyState
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.Field
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.PermissionResult
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.Thumb
import app.localcart.ui.components.rememberPermission
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogState(
    val loading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val saving: Boolean = false,
    val uploading: Boolean = false,
)

class CatalogViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CatalogState())
    val state = _state.asStateFlow()
    val items = container.catalog.items

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = items.value.isEmpty(), error = null) }
        viewModelScope.launch {
            try {
                container.catalog.load()
                _state.update { it.copy(loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun toggleStock(item: CatalogItemDto) = viewModelScope.launch {
        try {
            container.catalog.setStock(item.id, !item.inStock)
        } catch (e: ApiException) {
            _state.update { it.copy(notice = e.message) }
        }
    }

    fun save(id: String?, request: ItemRequest, onDone: () -> Unit) {
        _state.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            try {
                if (id == null) container.catalog.add(request) else container.catalog.update(id, request)
                _state.update { it.copy(saving = false) }
                onDone()
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, notice = e.message) }
            }
        }
    }

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                container.catalog.delete(id)
                onDone()
            } catch (e: ApiException) {
                _state.update { it.copy(notice = e.message) }
            }
        }
    }

    fun uploadPhoto(uri: Uri, context: android.content.Context, onUploaded: (String) -> Unit) {
        _state.update { it.copy(uploading = true, notice = null) }
        viewModelScope.launch {
            try {
                val file = ImageFiles.prepareJpeg(context, uri, maxSide = 1200)
                onUploaded(container.shops.uploadPhoto(file, "item"))
                file.delete()
                _state.update { it.copy(uploading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(uploading = false, notice = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, notice = "Couldn't read that photo.") }
            }
        }
    }

    fun notice(message: String?) = _state.update { it.copy(notice = message) }
}

@Composable
fun CatalogScreen(container: AppContainer, nav: Nav) {
    val vm: CatalogViewModel = viewModel { CatalogViewModel(container) }
    val state by vm.state.collectAsState()
    val items by vm.items.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // item id, or "new"

    Column(Modifier.fillMaxSize()) {
        ScreenHeading(
            title = "Catalog",
            subtitle = if (items.isEmpty()) null else "${items.size} items, ${items.count { !it.inStock }} out of stock",
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Add item", onClick = { editing = "new" }, icon = Icons.Outlined.Add, modifier = Modifier.weight(1f))
            SecondaryButton("Add from a list", onClick = nav::catalogImport, icon = Icons.Outlined.AutoAwesome, modifier = Modifier.weight(1f))
        }
        state.notice?.let { InlineNotice(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        when {
            state.loading -> LoadingState()
            state.error != null && items.isEmpty() -> ErrorState(state.error!!, onRetry = vm::load)
            items.isEmpty() -> EmptyState(
                title = "Your catalog is empty",
                body = "Add what you sell with prices. The fastest way is to type or paste your price list and let LocalCart read it.",
                icon = Icons.Outlined.Inventory2,
                action = "Add from a list",
                onAction = nav::catalogImport,
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.id }) { item ->
                    CatalogItemRow(item, onClick = { editing = item.id }, onToggleStock = { vm.toggleStock(item) })
                    HairlineDivider(Modifier.padding(start = 76.dp))
                }
            }
        }
    }

    editing?.let { id ->
        val item = items.firstOrNull { it.id == id }
        ItemEditorSheet(vm, state, item, onDismiss = { editing = null })
    }
}

@Composable
private fun CatalogItemRow(item: CatalogItemDto, onClick: () -> Unit, onToggleStock: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(item.photoUrl, item.name, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${Money.format(item.pricePaise)} per ${SellUnit.of(item.unit).label}", style = LocalFigures.current.small, color = Palette.InkMuted)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = item.inStock,
                onCheckedChange = { onToggleStock() },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Leaf, checkedThumbColor = Palette.Paper),
            )
            Text(if (item.inStock) "In stock" else "Out", style = MaterialTheme.typography.labelSmall, color = if (item.inStock) Palette.Leaf else Palette.Chilli)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemEditorSheet(vm: CatalogViewModel, state: CatalogState, item: CatalogItemDto?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(item?.id) { mutableStateOf(item?.name.orEmpty()) }
    var unit by rememberSaveable(item?.id) { mutableStateOf(item?.unit ?: "kg") }
    var price by rememberSaveable(item?.id) { mutableStateOf(item?.let { Money.toInput(it.pricePaise) }.orEmpty()) }
    var inStock by rememberSaveable(item?.id) { mutableStateOf(item?.inStock ?: true) }
    var photoUrl by rememberSaveable(item?.id) { mutableStateOf(item?.photoUrl) }
    var submitted by rememberSaveable(item?.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadPhoto(uri, context) { photoUrl = it }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture?.let(Uri::parse)
        if (ok && uri != null) vm.uploadPhoto(uri, context) { photoUrl = it }
    }
    val cameraPermission = rememberPermission(Manifest.permission.CAMERA) { result ->
        if (result == PermissionResult.Granted) {
            val (uri, _) = ImageFiles.newCaptureUri(context)
            pendingCapture = uri.toString()
            camera.launch(uri)
        } else {
            vm.notice("Camera permission is needed to take a photo. You can use the gallery instead.")
        }
    }
    val paise = Money.parseRupees(price)
    val nameError = if (submitted && name.isBlank()) "Enter the item name." else null
    val priceError = if (submitted && (paise == null || paise <= 0)) "Enter a price in rupees, e.g. 45 or 45.50." else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Paper) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(if (item == null) "Add item" else "Edit item", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(photoUrl, name.ifBlank { "Item" }, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                SecondaryButton("Camera", onClick = { cameraPermission.request() }, icon = Icons.Outlined.CameraAlt, enabled = !state.uploading)
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Gallery", onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Outlined.PhotoLibrary, enabled = !state.uploading)
            }
            Spacer(Modifier.height(12.dp))
            Field(name, { name = it }, label = "Item name", placeholder = "Amul Taaza Milk 500 ml", error = nameError)
            Spacer(Modifier.height(8.dp))
            Text("Sold per", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SellUnit.entries.forEach { u -> ChoiceChip(u.label, selected = unit == u.code, onClick = { unit = u.code }) }
            }
            Spacer(Modifier.height(12.dp))
            Field(price, { price = it }, label = "Price per ${SellUnit.of(unit).label}", prefix = "₹ ", keyboardType = KeyboardType.Decimal, error = priceError)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In stock", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = inStock, onCheckedChange = { inStock = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Leaf, checkedThumbColor = Palette.Paper))
            }
            state.notice?.let {
                InlineNotice(it)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton(
                if (item == null) "Add to catalog" else "Save item",
                loading = state.saving,
                enabled = !state.uploading,
                onClick = {
                    submitted = true
                    if (name.isNotBlank() && paise != null && paise > 0) {
                        vm.save(item?.id, ItemRequest(name.trim(), unit, paise, inStock, photoUrl), onDismiss)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (item != null) {
                QuietButton("Remove from catalog", onClick = { confirmDelete = true }, color = Palette.Chilli, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${item.name}?") },
            text = { Text("Customers won't see it any more. Past orders keep their history.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(item.id, onDismiss) }) { Text("Remove", color = Palette.Chilli) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
