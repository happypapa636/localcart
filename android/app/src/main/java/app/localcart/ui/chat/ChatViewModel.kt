package app.localcart.ui.chat

import ai.nexconn.chat.channel.query.MessagesQuery
import ai.nexconn.chat.message.HDVoiceMessage
import ai.nexconn.chat.message.Message
import ai.nexconn.chat.message.TextMessage
import ai.nexconn.chat.message.model.MessageDirection
import ai.nexconn.chat.message.model.SentStatus
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.localcart.chat.ChatEvent
import app.localcart.chat.OrderCard
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.data.api.OrderSummaryDto
import app.localcart.data.api.PeerDto
import app.localcart.media.ImageFiles
import app.localcart.ui.orders.OrderActionRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One row in the chat. `version` changes whenever the underlying SDK message changes. */
data class UiMessage(
    val key: String,
    val message: Message,
    val version: Int = 0,
    val progress: Int? = null,
    val failure: String? = null,
) {
    val mine: Boolean get() = message.direction == MessageDirection.SEND
    val sending: Boolean get() = message.sentStatus == SentStatus.SENDING && failure == null
}

fun keyOf(message: Message): String = if (message.clientId > 0) "c${message.clientId}" else "m${message.messageId}"

data class ChatState(
    val peer: PeerDto? = null,
    val messages: List<UiMessage> = emptyList(), // newest first (reverse layout)
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val typing: Boolean = false,
    val readIds: Set<String> = emptySet(),
    val activeOrders: List<OrderSummaryDto> = emptyList(),
    val makingCartFor: String? = null,
    val notice: String? = null,
    val noticeIsError: Boolean = true,
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
)

class ChatViewModel(
    private val container: AppContainer,
    val peerId: String,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val chat = container.chat
    private val _state = MutableStateFlow(ChatState(peer = container.peers.get(peerId)))
    val state = _state.asStateFlow()
    val runner = OrderActionRunner(viewModelScope, container.orders)

    /** Unsent text survives process death. */
    val draft = MutableStateFlow(saved.get<String>("draft").orEmpty())

    private var query: MessagesQuery? = null
    private var typingJob: Job? = null
    private var recordJob: Job? = null
    private val requestedOrders = mutableSetOf<String>()

    val isCustomerWithShop: Boolean get() = container.session.currentUser?.isShopkeeper == false && _state.value.peer?.kind == "shop"

    init {
        chat.activePeer = peerId
        viewModelScope.launch {
            if (_state.value.peer == null) runCatching { container.peers.refresh() }
            _state.update { it.copy(peer = container.peers.get(peerId)) }
            loadActiveOrders()
        }
        viewModelScope.launch { chat.events.collect(::onEvent) }
        viewModelScope.launch {
            // Wait for the SDK database before the first query (offline-first: local history shows immediately).
            repeat(20) {
                if (loadFirstPage()) return@launch
                delay(500)
            }
            _state.update { it.copy(loading = false) }
        }
    }

    override fun onCleared() {
        if (chat.activePeer == peerId) chat.activePeer = null
        container.voiceRecorder.stopAndDiscard()
        super.onCleared()
    }

    fun onScreenVisible(visible: Boolean) {
        chat.activePeer = if (visible) peerId else chat.activePeer.takeIf { it != peerId }
        if (visible) markRead()
    }

    private suspend fun loadFirstPage(): Boolean {
        return try {
            val q = chat.historyQuery(peerId)
            val page = chat.loadPage(q)
            query = q
            _state.update { s ->
                s.copy(loading = false, hasMore = q.hasMore, messages = merge(s.messages, page.map { UiMessage(keyOf(it), it) }))
            }
            page.forEach(::ensureOrder)
            markRead()
            refreshReceipts()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadMore() {
        val q = query ?: return
        val s = _state.value
        if (s.loadingMore || !s.hasMore || q.isLoading) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = chat.loadPage(q)
                _state.update { it.copy(loadingMore = false, hasMore = q.hasMore, messages = merge(it.messages, page.map { m -> UiMessage(keyOf(m), m) })) }
                page.forEach(::ensureOrder)
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Merge by key, newest first, de-duplicating messages delivered twice. */
    private fun merge(current: List<UiMessage>, incoming: List<UiMessage>): List<UiMessage> {
        val byKey = LinkedHashMap<String, UiMessage>()
        current.forEach { byKey[it.key] = it }
        incoming.forEach { msg ->
            val existing = byKey[msg.key]
            byKey[msg.key] = if (existing == null) msg else msg.copy(version = existing.version + 1)
        }
        // Drop a duplicate that shares a server id under a different key.
        val seenIds = HashSet<String>()
        return byKey.values
            .sortedByDescending { it.message.sentTime.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }
            .filter { ui -> ui.message.messageId?.let(seenIds::add) ?: true }
    }

    private fun isThisChannel(message: Message) = message.channelIdentifier.channelId == peerId

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Received -> if (isThisChannel(event.message)) {
                upsert(UiMessage(keyOf(event.message), event.message))
                ensureOrder(event.message)
                _state.update { it.copy(typing = false) }
                if (chat.activePeer == peerId) markRead()
                if (event.message.content is OrderCard) loadActiveOrders()
            }
            is ChatEvent.Attached -> if (isThisChannel(event.message)) upsert(UiMessage(keyOf(event.message), event.message))
            is ChatEvent.Sent -> if (isThisChannel(event.message)) upsert(UiMessage(keyOf(event.message), event.message))
            is ChatEvent.Progress -> if (isThisChannel(event.message)) upsert(UiMessage(keyOf(event.message), event.message, progress = event.percent))
            is ChatEvent.Failed -> if (isThisChannel(event.message)) upsert(UiMessage(keyOf(event.message), event.message, failure = event.error))
            is ChatEvent.Updated -> event.messages.filter(::isThisChannel).forEach { updated ->
                _state.update { s ->
                    s.copy(messages = s.messages.map { if (it.message.messageId == updated.messageId) it.copy(message = updated, version = it.version + 1) else it })
                }
            }
            is ChatEvent.Metadata -> if (isThisChannel(event.message)) {
                val messageId = event.message.messageId
                _state.update { s ->
                    s.copy(messages = s.messages.map { ui ->
                        if (ui.message.messageId == messageId) {
                            // Keep the content we already have; take the merged metadata.
                            ui.message.metadata = (ui.message.metadata.orEmpty() + event.metadata)
                            ui.copy(version = ui.version + 1)
                        } else ui
                    })
                }
                (event.message.content as? OrderCard)?.orderId?.let { refreshOrder(it) }
                loadActiveOrders()
            }
            is ChatEvent.Receipts -> _state.update { it.copy(readIds = it.readIds + event.messageIds) }
            is ChatEvent.Typing -> if (event.peerId == peerId) {
                _state.update { it.copy(typing = event.typing) }
                typingJob?.cancel()
                if (event.typing) typingJob = viewModelScope.launch {
                    delay(6_000)
                    _state.update { it.copy(typing = false) }
                }
            }
            is ChatEvent.SendError -> if (event.peerId == peerId) notice("Couldn't send that message. ${event.error}")
            is ChatEvent.Deleted -> {
                val keys = event.messages.map(::keyOf).toSet()
                _state.update { s -> s.copy(messages = s.messages.filterNot { it.key in keys }) }
            }
        }
    }

    private fun upsert(ui: UiMessage) {
        _state.update { s ->
            val existing = s.messages.firstOrNull { it.key == ui.key }
            val next = if (existing != null) ui.copy(version = existing.version + 1) else ui
            s.copy(messages = merge(s.messages.filterNot { it.key == ui.key }, listOf(next)))
        }
    }

    private fun ensureOrder(message: Message) {
        val card = message.content as? OrderCard ?: return
        if (card.orderId.isBlank() || !requestedOrders.add(card.orderId)) return
        refreshOrder(card.orderId)
    }

    private fun refreshOrder(orderId: String) {
        viewModelScope.launch { runCatching { container.orders.refresh(orderId) } }
    }

    private fun loadActiveOrders() {
        viewModelScope.launch {
            runCatching { container.orders.list("active", peerId) }.onSuccess { list -> _state.update { it.copy(activeOrders = list) } }
        }
    }

    fun markRead() {
        chat.markRead(peerId, _state.value.messages.take(50).map { it.message })
    }

    private fun refreshReceipts() {
        viewModelScope.launch {
            val mine = _state.value.messages.filter { it.mine && it.message.needReceipt }.mapNotNull { it.message.messageId }.take(30)
            val read = runCatching { chat.readMessageIds(peerId, mine) }.getOrDefault(emptySet())
            if (read.isNotEmpty()) _state.update { it.copy(readIds = it.readIds + read) }
        }
    }

    // ---- Sending ---------------------------------------------------------------------------

    fun onDraftChange(text: String) {
        draft.value = text
        saved["draft"] = text
        if (text.isNotBlank()) chat.sendTyping(peerId)
    }

    fun sendText(makeCart: Boolean = false) {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        chat.sendText(peerId, text)
        onDraftChange("")
        if (makeCart) makeCartFromText("draft-${System.nanoTime()}", text)
    }

    fun sendImage(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val file = ImageFiles.prepareJpeg(context, uri)
                chat.sendImage(peerId, file)
            } catch (e: Exception) {
                notice("Couldn't read that photo. Try another one.")
            }
        }
    }

    fun shareLocation() {
        if (!container.location.isEnabled()) {
            notice("Location is off on this phone. Turn it on to share where to deliver.")
            return
        }
        notice("Getting your location", isError = false)
        viewModelScope.launch {
            val fix = container.location.current()
            if (fix == null) {
                notice("Couldn't get your location. Try again in a moment.")
                return@launch
            }
            val address = container.location.address(fix.latitude, fix.longitude) ?: "Delivery location"
            try {
                chat.sendLocation(peerId, fix.latitude, fix.longitude, address)
            } catch (e: ApiException) {
                notice(e.message)
                return@launch
            }
            // Attach it to the active order so the shop has it on the order screen too.
            val active = _state.value.activeOrders.firstOrNull()
            if (active != null && container.session.currentUser?.isShopkeeper == false) {
                runCatching { container.orders.setDeliveryLocation(active.id, fix.latitude, fix.longitude, address) }
                notice("Location shared and saved on order #${active.code}", isError = false)
            } else {
                notice(null)
            }
        }
    }

    fun resend(ui: UiMessage) = chat.resend(ui.message)

    fun startRecording(): Boolean {
        if (!container.voiceRecorder.start()) {
            notice("Couldn't start the microphone. Close other apps using it and try again.")
            return false
        }
        _state.update { it.copy(recording = true, recordSeconds = 0) }
        recordJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val seconds = container.voiceRecorder.elapsedSeconds()
                _state.update { it.copy(recordSeconds = seconds) }
                if (seconds >= app.localcart.media.VoiceRecorder.MAX_SECONDS) {
                    finishRecording(send = true)
                    break
                }
            }
        }
        return true
    }

    fun finishRecording(send: Boolean) {
        recordJob?.cancel()
        recordJob = null
        _state.update { it.copy(recording = false, recordSeconds = 0) }
        if (!send) {
            container.voiceRecorder.stopAndDiscard()
            return
        }
        val result = container.voiceRecorder.stop()
        if (result == null) {
            notice("That voice note was too short. Hold on a little longer.")
            return
        }
        chat.sendVoice(peerId, result.first, result.second)
    }

    // ---- AI cart -----------------------------------------------------------------------------

    fun canMakeCart(ui: UiMessage): Boolean {
        if (!isCustomerWithShop || !ui.mine || ui.failure != null) return false
        return when (val c = ui.message.content) {
            is TextMessage -> c.text.isNotBlank()
            is HDVoiceMessage -> c.localPath?.let { File(it.removePrefix("file://")).exists() } == true
            else -> false
        }
    }

    fun makeCart(ui: UiMessage) {
        when (val c = ui.message.content) {
            is TextMessage -> makeCartFromText(ui.key, c.text)
            is HDVoiceMessage -> c.localPath?.let { makeCartFromVoice(ui.key, File(it.removePrefix("file://"))) }
            else -> Unit
        }
    }

    private fun makeCartFromText(key: String, text: String) = makeCartWith(key) { shopId ->
        container.orders.draftFromText(shopId, text)
    }

    private fun makeCartFromVoice(key: String, file: File) = makeCartWith(key) { shopId ->
        container.orders.draftFromVoice(shopId, file)
    }

    private fun makeCartWith(key: String, block: suspend (String) -> app.localcart.data.api.DraftResponse) {
        val shopId = _state.value.peer?.shopId ?: return notice("Couldn't find this shop. Reopen the chat and try again.")
        if (_state.value.makingCartFor != null) return
        _state.update { it.copy(makingCartFor = key, notice = null) }
        viewModelScope.launch {
            try {
                val result = block(shopId)
                requestedOrders.add(result.order.id)
                val heard = result.transcript?.takeIf { result.order.source == "voice" }?.let { "Heard: \"$it\". " }.orEmpty()
                val unmatched = result.order.items.count { it.catalogItemId == null }
                notice(
                    heard + "Cart #${result.order.code} ready with ${result.order.items.size} items" +
                        (if (unmatched > 0) ", $unmatched for the shop to price" else "") + ". Check it and send it to the shop.",
                    isError = false,
                )
                loadActiveOrders()
            } catch (e: ApiException) {
                notice(e.message)
            } finally {
                _state.update { it.copy(makingCartFor = null) }
            }
        }
    }

    fun notice(message: String?, isError: Boolean = true) = _state.update { it.copy(notice = message, noticeIsError = isError) }
}
