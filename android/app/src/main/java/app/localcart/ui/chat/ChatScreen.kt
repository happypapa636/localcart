package app.localcart.ui.chat

import ai.nexconn.chat.message.HDVoiceMessage
import ai.nexconn.chat.message.ImageMessage
import ai.nexconn.chat.message.LocationMessage
import ai.nexconn.chat.message.TextMessage
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.chat.BillCardMessage
import app.localcart.chat.CardState
import app.localcart.chat.CartCardMessage
import app.localcart.chat.ChatConnection
import app.localcart.chat.StatusCardMessage
import app.localcart.core.AppContainer
import app.localcart.data.api.OrderDto
import app.localcart.domain.OrderStatus
import app.localcart.media.ImageFiles
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.LoadingState
import app.localcart.ui.components.PermissionResult
import app.localcart.ui.components.QuietButton
import app.localcart.ui.components.StatusTag
import app.localcart.ui.components.Thumb
import app.localcart.ui.components.openAppSettings
import app.localcart.ui.components.rememberPermission
import app.localcart.ui.nav.Nav
import app.localcart.ui.orders.CancelDialog
import app.localcart.ui.orders.CartEditSheet
import app.localcart.ui.orders.NoUpiAppDialog
import app.localcart.ui.orders.PaymentDialog
import app.localcart.ui.orders.RateDialog
import app.localcart.ui.orders.launchUpi
import app.localcart.ui.orders.statusTone
import app.localcart.ui.theme.Palette
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(container: AppContainer, peerId: String, nav: Nav) {
    val vm: ChatViewModel = viewModel(key = "chat-$peerId") { ChatViewModel(container, peerId, createSavedStateHandle()) }
    val state by vm.state.collectAsState()
    val draft by vm.draft.collectAsState()
    val connection by container.chat.connection.collectAsState()
    val orders by container.orders.orders.collectAsState()
    val playing by container.voicePlayer.playing.collectAsState()
    val busy by vm.runner.busy.collectAsState()
    val actionError by vm.runner.error.collectAsState()
    val context = LocalContext.current
    val me = container.session.currentUser
    val viewerIsCustomer = me?.isShopkeeper == false
    val listState = rememberLazyListState()

    // Read receipts and notification suppression follow the screen's visibility.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onScreenVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.onScreenVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.onScreenVisible(false)
            container.voicePlayer.stop()
        }
    }
    // Load older history when the user scrolls near the top (reverse layout: the end of the list).
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.messages.size - 5) vm.loadMore()
        }
    }
    // Keep the newest message in view when something new arrives and we're already at the bottom.
    LaunchedEffect(state.messages.firstOrNull()?.key) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    var editingOrder by remember { mutableStateOf<OrderDto?>(null) }
    var cancellingOrder by remember { mutableStateOf<OrderDto?>(null) }
    var payingOrder by remember { mutableStateOf<OrderDto?>(null) }
    var ratingOrder by remember { mutableStateOf<OrderDto?>(null) }
    var noUpi by remember { mutableStateOf<OrderDto?>(null) }

    fun payWithUpi(order: OrderDto) {
        val link = container.orders.orders.value[order.id]?.upiLink ?: order.upiLink
        if (link == null || !launchUpi(context, link)) noUpi = order
    }

    val actions = CardActions(
        busy = busy,
        openOrder = nav::order,
        confirm = { id -> vm.runner.run("confirm-$id", onSuccess = { vm.notice(null) }) { confirm(id) } },
        edit = { editingOrder = it },
        discard = { cancellingOrder = it },
        prepareBill = nav::bill,
        accept = { payingOrder = it },
        decline = { cancellingOrder = it },
        payUpi = ::payWithUpi,
        markPaid = { id -> vm.runner.run("paid-$id") { payment(id, "marked_paid") } },
        confirmPayment = { id -> vm.runner.run("payment-$id") { payment(id, "confirmed") } },
        advance = { id, next -> vm.runner.run("advance-$id") { advance(id, next) } },
        cancel = { cancellingOrder = it },
        rate = { ratingOrder = it },
    )

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().imePadding()) {
        AppTopBar(
            title = state.peer?.name ?: "Chat",
            subtitle = when {
                state.typing -> "typing…"
                state.peer?.kind == "shop" -> "Shop"
                state.peer?.kind == "customer" -> "Customer"
                else -> null
            },
            onBack = nav::back,
            actions = {
                val shopId = state.peer?.shopId
                if (viewerIsCustomer && shopId != null) QuietButton("Shop", onClick = { nav.shop(shopId) })
            },
        )
        ConnectionStrip(connection, onRetry = container.chat::reconnect)
        if (state.activeOrders.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Palette.InkTint)) {
                state.activeOrders.take(2).forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.order(o.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Order #${o.code}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusTag(OrderStatus.label(o.status), statusTone(o.status))
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (state.loading) {
                LoadingState(label = "Loading messages")
            } else if (state.messages.isEmpty()) {
                EmptyChatHint(viewerIsCustomer = viewerIsCustomer, peerName = state.peer?.name)
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.messages, key = { it.key }) { ui ->
                    MessageRow(
                        ui = ui,
                        read = ui.message.messageId?.let { it in state.readIds } == true,
                        orders = orders,
                        viewerIsCustomer = viewerIsCustomer,
                        actions = actions,
                        playing = playing == ui.key,
                        onPlay = { source -> container.voicePlayer.toggle(ui.key, source) },
                        canMakeCart = vm.canMakeCart(ui),
                        makingCart = state.makingCartFor == ui.key,
                        onMakeCart = { vm.makeCart(ui) },
                        onRetry = { vm.resend(ui) },
                        onOpenUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    )
                }
                if (state.loadingMore) item { LoadingState(label = "Loading older messages") }
            }
        }
        val notice = state.notice ?: actionError
        if (notice != null) {
            InlineNotice(
                notice,
                isError = if (state.notice != null) state.noticeIsError else true,
                action = "Dismiss",
                onAction = { vm.notice(null); vm.runner.clearError() },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (state.makingCartFor != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Leaf)
                Spacer(Modifier.width(10.dp))
                Text("Reading your list and matching it to the shop's catalog", style = MaterialTheme.typography.bodySmall)
            }
        }
        Composer(
            vm = vm,
            draft = draft,
            recording = state.recording,
            recordSeconds = state.recordSeconds,
            showMakeCart = vm.isCustomerWithShop,
            makingCart = state.makingCartFor != null,
        )
    }

    editingOrder?.let { order ->
        CartEditSheet(order, container.shops, saving = busy == "edit-${order.id}", error = actionError, onDismiss = { editingOrder = null }) { lines ->
            vm.runner.run("edit-${order.id}", onSuccess = { editingOrder = null }) { editCart(order.id, lines) }
        }
    }
    cancellingOrder?.let { order ->
        CancelDialog(asShop = !viewerIsCustomer, onDismiss = { cancellingOrder = null }) { reason ->
            cancellingOrder = null
            vm.runner.run("cancel-${order.id}") { cancel(order.id, reason) }
        }
    }
    payingOrder?.let { order ->
        PaymentDialog(order.totalPaise, order.shop.upiId, onDismiss = { payingOrder = null }) { method ->
            payingOrder = null
            vm.runner.run("accept-${order.id}", onSuccess = { if (method == "upi") payWithUpi(order) }) { accept(order.id, method) }
        }
    }
    ratingOrder?.let { order ->
        RateDialog(order.shop.name, onDismiss = { ratingOrder = null }) { stars, comment ->
            ratingOrder = null
            vm.runner.run("rate-${order.id}") { rate(order.id, stars, comment) }
        }
    }
    noUpi?.let { order ->
        val upi = order.shop.upiId
        if (upi != null) NoUpiAppDialog(upi, container.orders.orders.value[order.id]?.totalPaise ?: order.totalPaise, context) { noUpi = null }
        else noUpi = null
    }
}

@Composable
private fun ConnectionStrip(connection: ChatConnection, onRetry: () -> Unit) {
    val (text, retry) = when (connection) {
        ChatConnection.Connected, ChatConnection.Idle -> return
        ChatConnection.Connecting -> "Connecting to chat" to false
        ChatConnection.Reconnecting -> "Reconnecting" to false
        ChatConnection.Offline -> "You're offline. Messages send when you reconnect." to false
        ChatConnection.SignedInElsewhere -> "Chat is open on another phone." to true
        ChatConnection.Failed -> "Chat couldn't connect." to true
    }
    Row(
        Modifier.fillMaxWidth().background(if (retry) Palette.ChilliTint else Palette.InkTint).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (retry) Palette.Chilli else Palette.InkMuted, modifier = Modifier.weight(1f))
        if (retry) Text("Retry", style = MaterialTheme.typography.labelLarge, color = Palette.Chilli, modifier = Modifier.clickable(onClick = onRetry).padding(4.dp))
    }
}

@Composable
private fun EmptyChatHint(viewerIsCustomer: Boolean, peerName: String?) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (viewerIsCustomer) "What do you need today?" else "No messages yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (viewerIsCustomer) {
                "Type your list, like \"2 kg aloo, 1 litre Amul milk, 5 eggs\", or tap the mic and say it in Hindi or English. Then tap Make cart and ${peerName ?: "the shop"} gets a ready cart."
            } else {
                "When this customer sends a cart or a message, it appears here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.InkMuted,
        )
    }
}

private val timeFormat = SimpleDateFormat("h:mm a", Locale.forLanguageTag("en-IN"))

@Composable
private fun MessageRow(
    ui: UiMessage,
    read: Boolean,
    orders: Map<String, OrderDto>,
    viewerIsCustomer: Boolean,
    actions: CardActions,
    playing: Boolean,
    onPlay: (String) -> Unit,
    canMakeCart: Boolean,
    makingCart: Boolean,
    onMakeCart: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val content = ui.message.content
    val meta = CardState.from(ui.message.metadata)
    // Cards are documents rather than speech: they sit full width.
    when (content) {
        is CartCardMessage -> return CardFrame { CartCard(content, meta, orders[content.orderId], viewerIsCustomer, actions) }
        is BillCardMessage -> return CardFrame { BillCard(content, meta, orders[content.orderId], viewerIsCustomer, actions) }
        is StatusCardMessage -> return CardFrame { StatusCard(content, meta, orders[content.orderId], viewerIsCustomer, actions) }
        else -> Unit
    }
    val mine = ui.mine
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp)
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (mine) Palette.Ink else Palette.Sheet)
                .then(if (ui.failure != null) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(if (content is ImageMessage) 4.dp else 12.dp),
        ) {
            val fg = if (mine) Palette.Paper else Palette.Ink
            when (content) {
                is TextMessage -> Text(content.text, style = MaterialTheme.typography.bodyLarge, color = fg)
                is ImageMessage -> {
                    val source = content.remoteUrl ?: content.localPath
                    AsyncImage(
                        model = source,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).clickable { content.remoteUrl?.let(onOpenUrl) },
                    )
                }
                is HDVoiceMessage -> {
                    val source = content.localPath?.takeIf { java.io.File(it.removePrefix("file://")).exists() } ?: content.remoteUrl
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Voice note, ${content.duration} seconds" }) {
                        IconButton(onClick = { source?.let(onPlay) }, enabled = source != null) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = fg)
                        }
                        Column {
                            Text("Voice note", style = MaterialTheme.typography.titleSmall, color = fg)
                            Text("%d:%02d".format(content.duration / 60, content.duration % 60), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                        }
                    }
                }
                is LocationMessage -> Row(
                    Modifier.clickable { onOpenUrl("geo:${content.latitude},${content.longitude}?q=${content.latitude},${content.longitude}") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(content.poi?.takeIf { it.isNotBlank() } ?: "Shared location", style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("%.5f, %.5f. Tap to open in maps".format(content.latitude, content.longitude), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
                    }
                }
                else -> Text("This message type isn't supported in this version.", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.7f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            if (ui.message.sentTime > 0) Text(timeFormat.format(Date(ui.message.sentTime)), style = MaterialTheme.typography.labelSmall, color = Palette.InkFaint)
            if (mine) {
                Spacer(Modifier.width(4.dp))
                when {
                    ui.failure != null -> {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Palette.Chilli, modifier = Modifier.size(14.dp))
                        Text(" ${ui.failure} Tap to retry", style = MaterialTheme.typography.labelSmall, color = Palette.Chilli)
                    }
                    ui.sending -> {
                        Icon(Icons.Outlined.Schedule, contentDescription = "Sending", tint = Palette.InkFaint, modifier = Modifier.size(14.dp))
                        ui.progress?.let { Text(" $it%", style = MaterialTheme.typography.labelSmall, color = Palette.InkFaint) }
                    }
                    read -> Icon(Icons.Outlined.DoneAll, contentDescription = "Read", tint = Palette.Leaf, modifier = Modifier.size(15.dp))
                    else -> Icon(Icons.Outlined.Done, contentDescription = "Sent", tint = Palette.InkFaint, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (canMakeCart) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !makingCart, onClick = onMakeCart)
                    .background(Palette.LeafTint).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (makingCart) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.Leaf)
                else Icon(Icons.Outlined.ShoppingBasket, contentDescription = null, tint = Palette.Leaf, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (makingCart) "Making cart" else "Make cart", style = MaterialTheme.typography.labelLarge, color = Palette.Leaf)
            }
        }
    }
}

@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 420.dp)) { content() }
    }
}

@Composable
private fun Composer(vm: ChatViewModel, draft: String, recording: Boolean, recordSeconds: Int, showMakeCart: Boolean, makingCart: Boolean) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.sendImage(uri, context) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture?.let(Uri::parse)
        if (ok && uri != null) vm.sendImage(uri, context)
    }
    val cameraPermission = rememberPermission(Manifest.permission.CAMERA) { result ->
        when (result) {
            PermissionResult.Granted -> {
                val (uri, _) = ImageFiles.newCaptureUri(context)
                pendingCapture = uri.toString()
                camera.launch(uri)
            }
            else -> vm.notice("Camera permission is needed to take a photo. You can send one from the gallery instead.")
        }
    }
    val locationPermission = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) { result ->
        when (result) {
            PermissionResult.Granted -> vm.shareLocation()
            PermissionResult.Denied -> vm.notice("Location permission is needed to share where to deliver.")
            PermissionResult.Blocked -> {
                vm.notice("Location permission is off for LocalCart. Turn it on in Settings.")
                context.openAppSettings()
            }
        }
    }
    val micPermission = rememberPermission(Manifest.permission.RECORD_AUDIO) { result ->
        when (result) {
            PermissionResult.Granted -> vm.startRecording()
            PermissionResult.Denied -> vm.notice("Microphone access is needed to record a voice note. You can type your list instead.")
            PermissionResult.Blocked -> {
                vm.notice("Microphone permission is off for LocalCart. Turn it on in Settings to send voice notes.")
                context.openAppSettings()
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(Palette.Sheet).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
        if (recording) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.finishRecording(send = false) }) { Icon(Icons.Outlined.Close, contentDescription = "Discard voice note", tint = Palette.Chilli) }
                Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Chilli))
                Spacer(Modifier.width(8.dp))
                Text("Recording %d:%02d".format(recordSeconds / 60, recordSeconds % 60), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("Speak your list", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { vm.finishRecording(send = true) }) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send voice note", tint = Palette.Leaf)
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Attach") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Photo from gallery") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = {
                        menu = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) }, onClick = {
                        menu = false
                        cameraPermission.request()
                    })
                    DropdownMenuItem(text = { Text("Share my location") }, leadingIcon = { Icon(Icons.Outlined.Place, null) }, onClick = {
                        menu = false
                        locationPermission.request()
                    })
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = vm::onDraftChange,
                placeholder = { Text(if (showMakeCart) "Type your list or a message" else "Message", color = Palette.InkFaint) },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Leaf,
                    unfocusedBorderColor = Palette.RuleStrong,
                    focusedContainerColor = Palette.Paper,
                    unfocusedContainerColor = Palette.Paper,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            if (draft.isBlank()) {
                IconButton(onClick = { micPermission.request() }) { Icon(Icons.Outlined.Mic, contentDescription = "Record voice note") }
            } else {
                if (showMakeCart) {
                    IconButton(onClick = { vm.sendText(makeCart = true) }, enabled = !makingCart) {
                        Icon(Icons.Outlined.ShoppingBasket, contentDescription = "Send and make cart", tint = Palette.Leaf)
                    }
                }
                IconButton(onClick = { vm.sendText() }) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", tint = Palette.Leaf) }
            }
        }
    }
}
