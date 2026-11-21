package app.localcart.ui.nav

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.localcart.chat.ChatConnection
import app.localcart.core.AppContainer
import app.localcart.data.api.UserDto
import app.localcart.data.repo.Session
import app.localcart.ui.account.AccountScreen
import app.localcart.ui.auth.SignInScreen
import app.localcart.ui.auth.SignUpScreen
import app.localcart.ui.auth.WelcomeScreen
import app.localcart.ui.chat.BillEditorScreen
import app.localcart.ui.chat.ChatListScreen
import app.localcart.ui.chat.ChatScreen
import app.localcart.ui.components.CountBadge
import app.localcart.ui.customer.DiscoverScreen
import app.localcart.ui.customer.LocationSetupScreen
import app.localcart.ui.customer.ShopDetailScreen
import app.localcart.ui.orders.OrderDetailScreen
import app.localcart.ui.orders.OrdersScreen
import app.localcart.ui.shop.BulkImportScreen
import app.localcart.ui.shop.CatalogScreen
import app.localcart.ui.shop.DashboardScreen
import app.localcart.ui.shop.ShopGate
import app.localcart.ui.shop.ShopSetupScreen
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable data class SignUpRoute(val role: String)
@Serializable object SignInRoute

@Serializable object HomeRoute
@Serializable data class ShopRoute(val shopId: String)
@Serializable data class ChatRoute(val peerId: String)
@Serializable data class OrderRoute(val orderId: String)
@Serializable data class BillRoute(val orderId: String)
@Serializable object CatalogImportRoute
@Serializable object ShopSettingsRoute
@Serializable object LocationRoute

/** Navigation actions shared by every screen. */
class Nav(private val controller: NavHostController) {
    fun back() { controller.popBackStack() }
    fun shop(id: String) = controller.navigate(ShopRoute(id))
    fun chat(peerId: String) = controller.navigate(ChatRoute(peerId)) { launchSingleTop = true }
    fun order(id: String) = controller.navigate(OrderRoute(id)) { launchSingleTop = true }
    fun bill(orderId: String) = controller.navigate(BillRoute(orderId))
    fun catalogImport() = controller.navigate(CatalogImportRoute)
    fun shopSettings() = controller.navigate(ShopSettingsRoute)
    fun location() = controller.navigate(LocationRoute)
}

@Composable
fun LocalCartRoot(container: AppContainer, pendingChat: MutableStateFlow<String?>) {
    val session by container.session.session.collectAsState()
    when (val s = session) {
        Session.SignedOut -> AuthFlow(container)
        is Session.SignedIn -> SignedInFlow(container, s.user, pendingChat)
    }
}

@Composable
private fun AuthFlow(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(onChooseRole = { nav.navigate(SignUpRoute(it)) }, onSignIn = { nav.navigate(SignInRoute) })
        }
        composable<SignUpRoute> { entry ->
            val route = entry.toRoute<SignUpRoute>()
            SignUpScreen(container, route.role, onBack = { nav.popBackStack() }, onSignIn = {
                nav.navigate(SignInRoute) { popUpTo(WelcomeRoute) }
            })
        }
        composable<SignInRoute> { SignInScreen(container, onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun SignedInFlow(container: AppContainer, user: UserDto, pendingChat: MutableStateFlow<String?>) {
    val controller = rememberNavController()
    val nav = Nav(controller)
    val openChat by pendingChat.collectAsState()
    LaunchedEffect(openChat) {
        openChat?.let {
            nav.chat(it)
            pendingChat.value = null
        }
    }
    NotificationPermissionOnce(container)

    Box(Modifier.fillMaxSize()) {
        NavHost(controller, startDestination = HomeRoute) {
            composable<HomeRoute> { HomeScreen(container, user, nav) }
            composable<ShopRoute> { entry -> ShopDetailScreen(container, entry.toRoute<ShopRoute>().shopId, nav) }
            composable<ChatRoute> { entry -> ChatScreen(container, entry.toRoute<ChatRoute>().peerId, nav) }
            composable<OrderRoute> { entry -> OrderDetailScreen(container, entry.toRoute<OrderRoute>().orderId, nav) }
            composable<BillRoute> { entry -> BillEditorScreen(container, entry.toRoute<BillRoute>().orderId, nav) }
            composable<CatalogImportRoute> { BulkImportScreen(container, nav) }
            composable<ShopSettingsRoute> { ShopSetupScreen(container, editing = true, onDone = { nav.back() }, onBack = { nav.back() }) }
            composable<LocationRoute> { LocationSetupScreen(container, onDone = { nav.back() }, onBack = { nav.back() }) }
        }
        MessageBanner(container, onOpen = { nav.chat(it) })
        SignedInElsewhereDialog(container)
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun HomeScreen(container: AppContainer, user: UserDto, nav: Nav) {
    if (user.isShopkeeper) {
        ShopGate(container) { ShopkeeperHome(container, nav) }
    } else if (!user.hasLocation) {
        LocationSetupScreen(container, onDone = {}, onBack = null)
    } else {
        CustomerHome(container, nav)
    }
}

@Composable
private fun CustomerHome(container: AppContainer, nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Shops", Icons.Outlined.Storefront),
        Tab("Orders", Icons.Outlined.ReceiptLong),
        Tab("Chats", Icons.Outlined.ChatBubbleOutline),
        Tab("Account", Icons.Outlined.PersonOutline),
    )
    TabScaffold(container, tabs, tab, chatTabIndex = 2, onSelect = { tab = it }) {
        when (tab) {
            0 -> DiscoverScreen(container, nav)
            1 -> OrdersScreen(container, nav)
            2 -> ChatListScreen(container, nav)
            else -> AccountScreen(container, nav)
        }
    }
}

@Composable
private fun ShopkeeperHome(container: AppContainer, nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Today", Icons.Outlined.Today),
        Tab("Orders", Icons.Outlined.ReceiptLong),
        Tab("Chats", Icons.Outlined.ChatBubbleOutline),
        Tab("Catalog", Icons.Outlined.Inventory2),
        Tab("Account", Icons.Outlined.PersonOutline),
    )
    TabScaffold(container, tabs, tab, chatTabIndex = 2, onSelect = { tab = it }) {
        when (tab) {
            0 -> DashboardScreen(container, nav, onOpenOrders = { tab = 1 }, onOpenChats = { tab = 2 })
            1 -> OrdersScreen(container, nav)
            2 -> ChatListScreen(container, nav)
            3 -> CatalogScreen(container, nav)
            else -> AccountScreen(container, nav)
        }
    }
}

@Composable
private fun TabScaffold(
    container: AppContainer,
    tabs: List<Tab>,
    selected: Int,
    chatTabIndex: Int,
    onSelect: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val unread by container.chat.totalUnread.collectAsState()
    Scaffold(
        containerColor = Palette.Paper,
        bottomBar = {
            NavigationBar(containerColor = Palette.Sheet, tonalElevation = 0.dp) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { onSelect(index) },
                        icon = {
                            if (index == chatTabIndex && unread > 0) {
                                BadgedBox(badge = { CountBadge(unread) }) { Icon(tab.icon, contentDescription = null) }
                            } else {
                                Icon(tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Leaf,
                            selectedTextColor = Palette.Leaf,
                            indicatorColor = Palette.LeafTint,
                            unselectedIconColor = Palette.InkMuted,
                            unselectedTextColor = Palette.InkMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Palette.Paper)) { content() }
    }
}

/** Foreground notification for messages from chats that are not on screen. */
@Composable
private fun MessageBanner(container: AppContainer, onOpen: (String) -> Unit) {
    val banner by container.notifier.banner.collectAsState()
    LaunchedEffect(banner?.id) {
        if (banner != null) {
            delay(4_500)
            container.notifier.dismissBanner()
        }
    }
    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val b = banner ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Palette.Ink,
            border = BorderStroke(1.dp, Palette.Ink),
            modifier = Modifier.fillMaxWidth().clickable {
                container.notifier.dismissBanner()
                onOpen(b.peerId)
            },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = Palette.Paper)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.titleSmall, color = Palette.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b.body, style = MaterialTheme.typography.bodyMedium, color = Palette.Paper.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SignedInElsewhereDialog(container: AppContainer) {
    val connection by container.chat.connection.collectAsState()
    if (connection != ChatConnection.SignedInElsewhere) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Signed in on another phone") },
        text = { Text("Chat works on one phone at a time. Use LocalCart here instead, or sign out on this phone.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { container.chat.reconnect() }) { Text("Use here") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { container.signOut() }) { Text("Sign out") } },
    )
}

/** Ask for notification permission once, so new carts and bills can alert while the app is in the background. */
@Composable
private fun NotificationPermissionOnce(container: AppContainer) {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (!container.session.notificationsAsked) {
            container.session.notificationsAsked = true
            delay(1_200)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
