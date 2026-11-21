package app.localcart.ui.customer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.ShopDto
import app.localcart.domain.Distance
import app.localcart.domain.Hours
import app.localcart.domain.ShopCategories
import app.localcart.ui.components.ChoiceChip
import app.localcart.ui.components.EmptyState
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.Field
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.StatusTag
import app.localcart.ui.components.Thumb
import app.localcart.ui.components.Tone
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val shops: List<ShopDto> = emptyList(),
    val category: String? = null,
    val query: String = "",
)

class DiscoverViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState(category = saved["category"], query = saved["query"] ?: ""))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun setCategory(category: String?) {
        saved["category"] = category
        _state.update { it.copy(category = category) }
        load()
    }

    fun setQuery(query: String) {
        saved["query"] = query
        _state.update { it.copy(query = query) }
        load(debounce = true)
    }

    fun refresh() = load(refreshing = true)

    fun load(debounce: Boolean = false, refreshing: Boolean = false) {
        val user = container.session.currentUser ?: return
        val lat = user.lat ?: return
        val lng = user.lng ?: return
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(350)
            _state.update { it.copy(loading = !refreshing && it.shops.isEmpty(), refreshing = refreshing, error = null) }
            try {
                val shops = container.shops.nearby(lat, lng, _state.value.category, _state.value.query)
                _state.update { it.copy(loading = false, refreshing = false, shops = shops) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(container: AppContainer, nav: Nav) {
    val vm: DiscoverViewModel = viewModel { DiscoverViewModel(container, createSavedStateHandle()) }
    val state by vm.state.collectAsState()
    val user by container.session.session.collectAsState()
    LaunchedEffect(container.session.currentUser?.lat, container.session.currentUser?.lng) { vm.load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeading(
            title = "Shops near you",
            subtitle = container.session.currentUser?.addressText?.let { "Delivering to $it" },
            trailing = { QuietButton("Change", onClick = { nav.location() }) },
        )
        Field(
            value = state.query,
            onValueChange = vm::setQuery,
            label = "Search shops or items",
            placeholder = "Amul milk, atta, bakery",
            trailing = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceChip("All", selected = state.category == null, onClick = { vm.setCategory(null) })
            ShopCategories.all.forEach { (code, _) ->
                ChoiceChip(ShopCategories.short(code), selected = state.category == code, onClick = { vm.setCategory(code) })
            }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState(label = "Finding shops near you")
                state.error != null -> ErrorState(state.error!!, onRetry = { vm.load() })
                state.shops.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            title = if (state.query.isNotBlank() || state.category != null) "No matching shops" else "No shops on LocalCart near you yet",
                            body = if (state.query.isNotBlank() || state.category != null) {
                                "Try another search or category."
                            } else {
                                "Shops within 10 km appear here. Ask your local kirana to join LocalCart, or change your delivery location."
                            },
                            icon = Icons.Outlined.Storefront,
                            action = if (state.query.isBlank() && state.category == null) "Change location" else null,
                            onAction = { nav.location() },
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.shops, key = { it.id }) { shop ->
                        ShopRow(shop, onClick = { nav.shop(shop.id) })
                        HairlineDivider(Modifier.padding(start = 92.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopRow(shop: ShopDto, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)
            .alpha(if (shop.openNow) 1f else 0.62f),
        verticalAlignment = Alignment.Top,
    ) {
        Thumb(shop.photoUrl, shop.name, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (shop.rating != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(14.dp))
                    Text("%.1f".format(shop.rating), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                listOfNotNull(ShopCategories.short(shop.category), Distance.format(shop.distanceKm)).joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (shop.openNow) StatusTag("Open now", Tone.Positive)
                else StatusTag(if (!shop.isOpen) "Closed today" else "Opens ${Hours.pretty(shop.opensAt)}", Tone.Negative)
                if (shop.deliversToYou == false) StatusTag("Pickup only", Tone.Neutral)
            }
        }
    }
}
