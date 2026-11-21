package app.localcart.ui.customer

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
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
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.UpdateMeRequest
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.Field
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.PermissionResult
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.openAppSettings
import app.localcart.ui.components.rememberPermission
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationUiState(
    val busy: Boolean = false,
    val locating: Boolean = false,
    val error: String? = null,
    val blocked: Boolean = false,
)

class LocationViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LocationUiState())
    val state = _state.asStateFlow()

    fun useDeviceLocation(onSaved: () -> Unit) {
        if (!container.location.isEnabled()) {
            _state.update { it.copy(error = "Location is turned off on this phone. Turn it on, or type your address below.") }
            return
        }
        _state.update { it.copy(locating = true, error = null) }
        viewModelScope.launch {
            val fix = container.location.current()
            if (fix == null) {
                _state.update { it.copy(locating = false, error = "Couldn't get a location fix. Try again near a window, or type your address.") }
                return@launch
            }
            val address = container.location.address(fix.latitude, fix.longitude)
            save(fix.latitude, fix.longitude, address ?: "Near %.4f, %.4f".format(fix.latitude, fix.longitude), onSaved)
        }
    }

    fun useTypedAddress(text: String, onSaved: () -> Unit) {
        if (text.trim().length < 5) {
            _state.update { it.copy(error = "Type your area and city, e.g. \"Indiranagar, Bengaluru\".") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val place = container.location.lookup(text.trim())
            if (place == null) {
                _state.update { it.copy(busy = false, error = "Couldn't find that address. Add your area and city, or use your current location.") }
                return@launch
            }
            save(place.lat, place.lng, text.trim(), onSaved)
        }
    }

    fun permissionResult(result: PermissionResult, onSaved: () -> Unit) {
        when (result) {
            PermissionResult.Granted -> useDeviceLocation(onSaved)
            PermissionResult.Denied -> _state.update { it.copy(error = "Without location, type your address below so we can find shops near you.") }
            PermissionResult.Blocked -> _state.update { it.copy(blocked = true, error = "Location permission is turned off for LocalCart. Allow it in Settings, or type your address.") }
        }
    }

    private suspend fun save(lat: Double, lng: Double, address: String, onSaved: () -> Unit) {
        _state.update { it.copy(busy = true, locating = false) }
        try {
            container.auth.updateMe(UpdateMeRequest(lat = lat, lng = lng, addressText = address))
            _state.update { LocationUiState() }
            onSaved()
        } catch (e: ApiException) {
            _state.update { it.copy(busy = false, error = e.message) }
        }
    }
}

/** Where should we deliver? GPS with a typed-address fallback. */
@Composable
fun LocationSetupScreen(container: AppContainer, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val vm: LocationViewModel = viewModel { LocationViewModel(container) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(container.session.currentUser?.addressText.orEmpty()) }
    val permission = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) {
        vm.permissionResult(it, onDone)
    }

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AppTopBar(title = if (onBack == null) "Where should shops deliver?" else "Delivery location", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                "We show shops near you, sorted by distance, and share this address with the shop when you order.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.InkMuted,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                "Use my current location",
                onClick = { permission.request() },
                icon = Icons.Outlined.MyLocation,
                loading = state.locating,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            Text("Or type your address", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Field(address, { address = it }, label = "House, street, area, city", singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Use this address", onClick = { vm.useTypedAddress(address, onDone) }, enabled = !state.busy && !state.locating, modifier = Modifier.fillMaxWidth())
            state.error?.let {
                Spacer(Modifier.height(16.dp))
                InlineNotice(it, action = if (state.blocked) "Settings" else null, onAction = { context.openAppSettings() })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
