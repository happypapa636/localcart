package app.localcart.ui.customer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.CatalogItemDto
import app.localcart.data.api.ShopDetailResponse
import app.localcart.domain.Distance
import app.localcart.domain.Hours
import app.localcart.domain.Money
import app.localcart.domain.ShopCategories
import app.localcart.domain.Unit as SellUnit
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.EmptyState
import app.localcart.ui.components.ErrorState
import app.localcart.ui.components.HairlineDivider
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.KeyValueRow
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.SectionLabel
import app.localcart.ui.components.Sheet
import app.localcart.ui.components.StatusTag
import app.localcart.ui.components.Thumb
import app.localcart.ui.components.Tone
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShopDetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: ShopDetailResponse? = null,
    val opening: Boolean = false,
    val actionError: String? = null,
)

class ShopDetailViewModel(private val container: AppContainer, private val shopId: String) : ViewModel() {
    private val _state = MutableStateFlow(ShopDetailState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.detail == null, error = null) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = false, detail = container.shops.detail(shopId)) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun openChat(onOpened: (String) -> Unit) {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true, actionError = null) }
        viewModelScope.launch {
            try {
                val peer = container.peers.openWithShop(shopId)
                _state.update { it.copy(opening = false) }
                onOpened(peer.nexconnId)
            } catch (e: ApiException) {
                _state.update { it.copy(opening = false, actionError = e.message) }
            }
        }
    }
}

@Composable
fun ShopDetailScreen(container: AppContainer, shopId: String, nav: Nav) {
    val vm: ShopDetailViewModel = viewModel(key = "shop-$shopId") { ShopDetailViewModel(container, shopId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val detail = state.detail

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding()) {
        AppTopBar(
            title = detail?.shop?.name ?: "Shop",
            onBack = nav::back,
            actions = {
                if (detail != null) {
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+91${detail.shop.phone}"))) }) {
                        Icon(Icons.Outlined.Call, contentDescription = "Call shop")
                    }
                }
            },
        )
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = vm::load)
            detail != null -> {
                val shop = detail.shop
                Box(Modifier.weight(1f)) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Thumb(shop.photoUrl, shop.name, size = 72.dp)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(shop.name, style = MaterialTheme.typography.headlineSmall)
                                    Text(ShopCategories.label(shop.category), style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (shop.openNow) StatusTag("Open now", Tone.Positive) else StatusTag("Closed now", Tone.Negative)
                                        if (shop.rating != null) StatusTag("Rated %.1f by %d".format(shop.rating, shop.ratingCount), Tone.Neutral)
                                    }
                                }
                            }
                        }
                        item {
                            Sheet(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Column {
                                    KeyValueRow("Hours", Hours.range(shop.opensAt, shop.closesAt))
                                    Distance.format(shop.distanceKm)?.let { KeyValueRow("Distance", it) }
                                    KeyValueRow(
                                        "Delivery",
                                        when (shop.deliversToYou) {
                                            false -> "Outside its ${"%.1f".format(shop.deliveryRadiusKm)} km area, pickup only"
                                            else -> "Within ${"%.1f".format(shop.deliveryRadiusKm)} km"
                                        },
                                    )
                                    KeyValueRow("Payment", if (shop.upiId != null) "UPI or cash" else "Cash on delivery")
                                    KeyValueRow("Address", shop.addressText)
                                }
                            }
                        }
                        item {
                            Text(
                                "In the chat, type or say your list (\"2 kg aloo, 1 litre Amul milk, 5 eggs\") and tap Make cart. The shop confirms prices with a bill.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.InkMuted,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                        item { SectionLabel("Catalog (${detail.items.size})") }
                        if (detail.items.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "No items listed yet",
                                    body = "You can still chat with the shop and ask what they have.",
                                    icon = Icons.Outlined.Inventory2,
                                )
                            }
                        }
                        items(detail.items, key = { it.id }) { item ->
                            CatalogRow(item)
                            HairlineDivider(Modifier.padding(start = 20.dp))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Palette.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    state.actionError?.let {
                        InlineNotice(it)
                        Spacer(Modifier.height(8.dp))
                    }
                    PrimaryButton(
                        if (shop.openNow) "Chat to order" else "Chat with shop",
                        onClick = { vm.openChat { peer -> nav.chat(peer) } },
                        loading = state.opening,
                        icon = Icons.Outlined.ChatBubbleOutline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!shop.openNow) {
                        Text(
                            "Closed now. You can message them; carts open at ${Hours.pretty(shop.opensAt)}.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(item: CatalogItemDto) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).alpha(if (item.inStock) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(item.photoUrl, item.name, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!item.inStock) Text("Out of stock", style = MaterialTheme.typography.labelMedium, color = Palette.Chilli)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.format(item.pricePaise), style = LocalFigures.current.medium)
            Text("per ${SellUnit.of(item.unit).label}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
