package app.localcart.ui.shop

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.ShopDto
import app.localcart.data.api.ShopRequest
import app.localcart.domain.Hours
import app.localcart.domain.Phone
import app.localcart.domain.ShopCategories
import app.localcart.domain.Upi
import app.localcart.media.ImageFiles
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.ChoiceChip
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.Field
import app.localcart.ui.components.FullScreenLoading
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.PermissionResult
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.Thumb
import app.localcart.ui.components.openAppSettings
import app.localcart.ui.components.rememberPermission
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GateState {
    data object Loading : GateState
    data class Error(val message: String) : GateState
    data object NoShop : GateState
    data object Ready : GateState
}

class ShopGateViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<GateState>(GateState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = GateState.Loading
        viewModelScope.launch {
            _state.value = try {
                if (container.shops.loadMyShop() == null) GateState.NoShop else GateState.Ready
            } catch (e: ApiException) {
                GateState.Error(e.message)
            }
        }
    }
}

/** Shopkeepers without a shop are taken through setup before they see the dashboard. */
@Composable
fun ShopGate(container: AppContainer, content: @Composable () -> Unit) {
    val vm: ShopGateViewModel = viewModel { ShopGateViewModel(container) }
    val state by vm.state.collectAsState()
    when (val s = state) {
        GateState.Loading -> FullScreenLoading()
        is GateState.Error -> Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding()) { ErrorState(s.message, onRetry = vm::load) }
        GateState.NoShop -> ShopSetupScreen(container, editing = false, onDone = vm::load, onBack = null)
        GateState.Ready -> content()
    }
}

data class ShopFormState(
    val uploading: Boolean = false,
    val locating: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val locationBlocked: Boolean = false,
)

class ShopSetupViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ShopFormState())
    val state = _state.asStateFlow()

    fun uploadPhoto(uri: Uri, context: android.content.Context, onUploaded: (String) -> Unit) {
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            try {
                val file = ImageFiles.prepareJpeg(context, uri)
                onUploaded(container.shops.uploadPhoto(file, "shop"))
                file.delete()
                _state.update { it.copy(uploading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(uploading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, error = "Couldn't read that photo. Try another one.") }
            }
        }
    }

    fun locate(onFound: (Double, Double, String?) -> Unit) {
        if (!container.location.isEnabled()) {
            _state.update { it.copy(error = "Location is off on this phone. Turn it on to pin your shop.") }
            return
        }
        _state.update { it.copy(locating = true, error = null) }
        viewModelScope.launch {
            val fix = container.location.current()
            if (fix == null) {
                _state.update { it.copy(locating = false, error = "Couldn't get a location fix. Try again in a moment.") }
                return@launch
            }
            onFound(fix.latitude, fix.longitude, container.location.address(fix.latitude, fix.longitude))
            _state.update { it.copy(locating = false) }
        }
    }

    fun permissionResult(result: PermissionResult, onFound: (Double, Double, String?) -> Unit) {
        when (result) {
            PermissionResult.Granted -> locate(onFound)
            PermissionResult.Denied -> _state.update { it.copy(error = "Location is needed to show your shop to nearby customers.") }
            PermissionResult.Blocked -> _state.update { it.copy(locationBlocked = true, error = "Location permission is off for LocalCart. Allow it in Settings.") }
        }
    }

    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun save(request: ShopRequest, editing: Boolean, onDone: () -> Unit) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (editing) container.shops.update(request) else container.shops.create(request)
                // The chat display name is the shop name, so refresh the cached chat token identity next time.
                _state.update { it.copy(saving = false) }
                onDone()
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShopSetupScreen(container: AppContainer, editing: Boolean, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val vm: ShopSetupViewModel = viewModel { ShopSetupViewModel(container) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val existing: ShopDto? = if (editing) container.shops.myShop.value else null
    val user = container.session.currentUser

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "kirana") }
    var phone by rememberSaveable { mutableStateOf(existing?.phone ?: user?.phone.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(existing?.addressText.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(existing?.lat) }
    var lng by rememberSaveable { mutableStateOf(existing?.lng) }
    var radius by rememberSaveable { mutableFloatStateOf(existing?.deliveryRadiusKm?.toFloat() ?: 3f) }
    var opensAt by rememberSaveable { mutableStateOf(existing?.opensAt ?: "08:00") }
    var closesAt by rememberSaveable { mutableStateOf(existing?.closesAt ?: "21:00") }
    var upi by rememberSaveable { mutableStateOf(existing?.upiId.orEmpty()) }
    var photoUrl by rememberSaveable { mutableStateOf(existing?.photoUrl) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    val onFound: (Double, Double, String?) -> Unit = { la, ln, addr ->
        lat = la
        lng = ln
        if (addr != null && address.isBlank()) address = addr
    }
    val locationPermission = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) {
        vm.permissionResult(it, onFound)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadPhoto(uri, context) { photoUrl = it }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture?.let(Uri::parse)
        if (ok && uri != null) vm.uploadPhoto(uri, context) { photoUrl = it }
    }
    val cameraPermission = rememberPermission(Manifest.permission.CAMERA) { result ->
        when (result) {
            PermissionResult.Granted -> {
                val (uri, _) = ImageFiles.newCaptureUri(context)
                pendingCapture = uri.toString()
                camera.launch(uri)
            }
            PermissionResult.Denied -> vm.showError("Camera access is needed to take a photo. You can pick one from the gallery instead.")
            PermissionResult.Blocked -> vm.showError("Camera permission is off for LocalCart. Allow it in Settings or use the gallery.")
        }
    }

    val nameError = if (submitted && name.trim().length < 2) "Enter the shop name." else null
    val phoneError = if (submitted && Phone.normalize(phone) == null) "Enter a valid 10-digit number." else null
    val addressError = if (submitted && address.trim().length < 5) "Enter the shop address." else null
    val locationError = if (submitted && (lat == null || lng == null)) "Pin the shop location so nearby customers can find you." else null
    val upiError = if (submitted && upi.isNotBlank() && !Upi.isValid(upi)) "Enter a valid UPI ID like shopname@okaxis." else null

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AppTopBar(title = if (editing) "Shop settings" else "Set up your shop", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (!editing) {
                Text("Customers nearby will see this when they look for shops.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                Spacer(Modifier.height(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(photoUrl, name.ifBlank { "Shop" }, size = 72.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (state.uploading) "Uploading photo" else "Shop photo", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Camera", onClick = { cameraPermission.request() }, icon = Icons.Outlined.CameraAlt, enabled = !state.uploading)
                        SecondaryButton("Gallery", onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Outlined.PhotoLibrary, enabled = !state.uploading)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Field(name, { name = it }, label = "Shop name", placeholder = "Sharma General Store", error = nameError)
            Spacer(Modifier.height(12.dp))
            Text("Category", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopCategories.all.forEach { (code, label) -> ChoiceChip(label, selected = category == code, onClick = { category = code }) }
            }
            Spacer(Modifier.height(16.dp))
            Field(phone, { phone = it }, label = "Shop phone", prefix = "+91 ", keyboardType = KeyboardType.Phone, error = phoneError)
            Spacer(Modifier.height(8.dp))
            Text("Location", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                if (lat != null) "Location pinned. Update from GPS" else "Pin shop location from GPS",
                onClick = { locationPermission.request() },
                icon = Icons.Outlined.MyLocation,
                enabled = !state.locating,
                modifier = Modifier.fillMaxWidth(),
                color = if (lat != null) Palette.Leaf else Palette.Ink,
            )
            if (lat != null && lng != null) {
                Text("%.5f, %.5f".format(lat, lng), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            locationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Chilli, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(8.dp))
            Field(address, { address = it }, label = "Shop address", singleLine = false, minLines = 2, error = addressError)
            Spacer(Modifier.height(12.dp))
            Text("Delivery radius: ${"%.1f".format(radius)} km", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = radius,
                onValueChange = { radius = (Math.round(it * 2) / 2f).coerceIn(0.5f, 10f) },
                valueRange = 0.5f..10f,
                steps = 18,
                colors = SliderDefaults.colors(thumbColor = Palette.Leaf, activeTrackColor = Palette.Leaf, inactiveTrackColor = Palette.Rule),
            )
            Text("Opening hours", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Opens ${Hours.pretty(opensAt)}", onClick = { pickingTime = "open" }, modifier = Modifier.weight(1f))
                SecondaryButton("Closes ${Hours.pretty(closesAt)}", onClick = { pickingTime = "close" }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Field(upi, { upi = it.trim() }, label = "UPI ID (optional)", placeholder = "sharmastore@okaxis", error = upiError, supporting = "Customers can pay you directly with any UPI app")
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                InlineNotice(it, action = if (state.locationBlocked) "Settings" else null, onAction = { context.openAppSettings() })
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                if (editing) "Save changes" else "Create shop",
                loading = state.saving,
                enabled = !state.uploading,
                onClick = {
                    submitted = true
                    val p = Phone.normalize(phone)
                    val la = lat
                    val ln = lng
                    if (name.trim().length >= 2 && p != null && address.trim().length >= 5 && la != null && ln != null && (upi.isBlank() || Upi.isValid(upi))) {
                        vm.save(
                            ShopRequest(
                                name = name.trim(),
                                category = category,
                                photoUrl = photoUrl,
                                phone = p,
                                addressText = address.trim(),
                                lat = la,
                                lng = ln,
                                deliveryRadiusKm = radius.toDouble(),
                                opensAt = opensAt,
                                closesAt = closesAt,
                                upiId = upi.ifBlank { null },
                            ),
                            editing,
                            onDone,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    pickingTime?.let { which ->
        val current = if (which == "open") opensAt else closesAt
        val parts = current.split(":")
        val pickerState = rememberTimePickerState(initialHour = parts[0].toInt(), initialMinute = parts[1].toInt(), is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(if (which == "open") "Opens at" else "Closes at") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val value = Hours.hhmm(pickerState.hour, pickerState.minute)
                    if (which == "open") opensAt = value else closesAt = value
                    pickingTime = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text("Cancel") } },
        )
    }
}
