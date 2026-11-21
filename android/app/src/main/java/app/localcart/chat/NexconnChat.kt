package app.localcart.chat

import ai.nexconn.chat.NCEngine
import ai.nexconn.chat.channel.BaseChannel
import ai.nexconn.chat.channel.ChannelType
import ai.nexconn.chat.channel.DirectChannel
import ai.nexconn.chat.channel.model.ChannelIdentifier
import ai.nexconn.chat.channel.model.TypingStatusChangedEvent
import ai.nexconn.chat.error.NCError
import ai.nexconn.chat.handler.ChannelHandler
import ai.nexconn.chat.handler.ConnectHandler
import ai.nexconn.chat.handler.ConnectionStatusHandler
import ai.nexconn.chat.handler.ErrorHandler
import ai.nexconn.chat.handler.MessageHandler
import ai.nexconn.chat.handler.OperationHandler
import ai.nexconn.chat.handler.SendMediaMessageHandler
import ai.nexconn.chat.handler.SendMessageHandler
import ai.nexconn.chat.message.HDVoiceMessage
import ai.nexconn.chat.message.ImageMessage
import ai.nexconn.chat.message.LocationMessage
import ai.nexconn.chat.message.Message
import ai.nexconn.chat.message.MessageContent
import ai.nexconn.chat.message.TextMessage
import ai.nexconn.chat.message.model.MessageDeletedEvent
import ai.nexconn.chat.message.model.MessageDirection
import ai.nexconn.chat.message.model.MessageMetadataUpdatedEvent
import ai.nexconn.chat.message.model.MessageReceiptResponseEvent
import ai.nexconn.chat.message.model.MessageReceivedEvent
import ai.nexconn.chat.message.model.MessagesUpdatedEvent
import ai.nexconn.chat.model.ConnectionStatus
import ai.nexconn.chat.model.ConnectionStatusChangedEvent
import ai.nexconn.chat.params.AreaCode
import ai.nexconn.chat.params.ChannelsQueryParams
import ai.nexconn.chat.params.ConnectParams
import ai.nexconn.chat.params.GetMessageByIdParams
import ai.nexconn.chat.params.InitParams
import ai.nexconn.chat.params.LogLevel
import ai.nexconn.chat.params.MessagesQueryParams
import ai.nexconn.chat.params.PushConfig
import ai.nexconn.chat.params.SendMediaMessageParams
import ai.nexconn.chat.params.SendMessageParams
import ai.nexconn.chat.channel.query.MessagesQuery
import android.content.Context
import android.util.Log
import app.localcart.BuildConfig
import app.localcart.data.api.LocalCartApi
import app.localcart.data.api.ShareLocationRequest
import app.localcart.data.api.TokenRequest
import app.localcart.data.api.apiCall
import app.localcart.data.repo.SessionRepository
import app.localcart.domain.Money
import app.localcart.media.LocationThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Connection state as the UI cares about it. */
enum class ChatConnection { Idle, Connecting, Connected, Offline, Reconnecting, SignedInElsewhere, Failed }

/** Realtime events fanned out from the Nexconn SDK handlers. */
sealed interface ChatEvent {
    data class Received(val message: Message) : ChatEvent
    data class Attached(val message: Message) : ChatEvent
    data class Sent(val message: Message) : ChatEvent
    data class Failed(val message: Message, val error: String) : ChatEvent
    data class Progress(val message: Message, val percent: Int) : ChatEvent
    data class Updated(val messages: List<Message>) : ChatEvent
    data class Metadata(val message: Message, val metadata: Map<String, String>) : ChatEvent
    data class Receipts(val messageIds: Set<String>) : ChatEvent
    data class Typing(val peerId: String, val typing: Boolean) : ChatEvent
    data class Deleted(val messages: List<Message>) : ChatEvent
    /** A send failed before the SDK produced a message (e.g. invalid content). */
    data class SendError(val peerId: String, val error: String) : ChatEvent
}

data class Conversation(
    val peerId: String,
    val unread: Int,
    val preview: String,
    val time: Long,
    val lastFromMe: Boolean,
)

class ChatException(val code: Int, message: String) : Exception(message)

/**
 * Thin, app-owned wrapper around the Nexconn Android Chat SDK (`NCEngine`, `DirectChannel`).
 * Owns initialisation, connection with token refresh, handler registration and coroutine adapters.
 */
class NexconnChat(
    private val context: Context,
    private val api: LocalCartApi,
    private val session: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val _connection = MutableStateFlow(ChatConnection.Idle)
    val connection: StateFlow<ChatConnection> = _connection.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    /** The peer whose chat is on screen, so notifications are not shown for it. */
    @Volatile var activePeer: String? = null

    @Volatile private var initialized = false
    @Volatile private var connectRequested = false
    private var tokenRefreshes = 0
    private var startJob: Job? = null

    val myUserId: String? get() = session.currentUser?.nexconnId

    /** Initialise the SDK (once per process), register card types and handlers, then connect. */
    fun start() {
        if (session.currentUser == null) return
        if (startJob?.isActive == true || connectRequested) return
        startJob = scope.launch {
            try {
                val appKey = session.appKey ?: apiCall { api.config() }.nexconnAppKey.also { session.appKey = it }
                initialize(appKey)
                val token = session.cachedChatToken() ?: fetchToken(refresh = false)
                connect(token)
            } catch (e: Exception) {
                Log.w(TAG, "chat start failed: ${e.message}")
                _connection.value = ChatConnection.Offline
                delay(5_000)
                startJob = null
                if (session.currentUser != null && !connectRequested) start()
            }
        }
    }

    private fun initialize(appKey: String) {
        if (initialized) return
        NCEngine.initialize(
            InitParams(context.applicationContext, appKey).apply {
                areaCode = AreaCode.SG // App Key lives in the Singapore data centre
                logLevel = if (BuildConfig.DEBUG) LogLevel.WARN else LogLevel.ERROR
                enablePush = false // FCM is not configured on the shared console; see README
            },
        )
        NCEngine.registerCustomMessages(
            listOf(CartCardMessage::class.java, BillCardMessage::class.java, StatusCardMessage::class.java),
        )
        registerHandlers()
        initialized = true
    }

    private suspend fun fetchToken(refresh: Boolean): String {
        val dto = apiCall { api.chatToken(TokenRequest(refresh)) }
        session.cacheChatToken(dto.token)
        return dto.token
    }

    private fun connect(token: String) {
        connectRequested = true
        _connection.value = ChatConnection.Connecting
        NCEngine.connect(
            ConnectParams(token).apply { timeout = 0 },
            object : ConnectHandler {
                override fun onResult(userId: String?, error: NCError?) {
                    if (error == null) {
                        tokenRefreshes = 0
                        _connection.value = ChatConnection.Connected
                        refreshUnread()
                        return
                    }
                    Log.w(TAG, "connect failed ${error.code} ${error.message}")
                    when (error.code) {
                        31004, 31020 -> refreshTokenAndReconnect()
                        34001 -> Unit // already connected or reconnecting
                        else -> {
                            connectRequested = false
                            _connection.value = ChatConnection.Failed
                        }
                    }
                }

                override fun onDatabaseOpened(isRecreated: Boolean, error: NCError?) {
                    if (error == null) refreshUnread()
                }
            },
        )
    }

    /** Invalid or expired chat token: get a fresh one from our server and connect again (bounded). */
    private fun refreshTokenAndReconnect() {
        if (tokenRefreshes >= 2) {
            connectRequested = false
            _connection.value = ChatConnection.Failed
            return
        }
        tokenRefreshes++
        scope.launch {
            try {
                NCEngine.disconnect(false)
                connectRequested = false
                connect(fetchToken(refresh = true))
            } catch (e: Exception) {
                connectRequested = false
                _connection.value = ChatConnection.Failed
            }
        }
    }

    /** Retry after a failure or after being signed out by another device. */
    fun reconnect() {
        connectRequested = false
        startJob?.cancel()
        startJob = null
        if (initialized) runCatching { NCEngine.disconnect(false) }
        start()
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        connectRequested = false
        tokenRefreshes = 0
        if (initialized) runCatching { NCEngine.disconnect(false) }
        _connection.value = ChatConnection.Idle
        _totalUnread.value = 0
    }

    private fun registerHandlers() {
        NCEngine.addConnectionStatusHandler(HANDLER_ID, object : ConnectionStatusHandler {
            override fun onConnectionStatusChanged(event: ConnectionStatusChangedEvent) {
                _connection.value = when (event.status) {
                    ConnectionStatus.CONNECTED -> ChatConnection.Connected
                    ConnectionStatus.CONNECTING -> ChatConnection.Connecting
                    ConnectionStatus.SUSPENDED -> ChatConnection.Reconnecting
                    ConnectionStatus.NETWORK_UNAVAILABLE -> ChatConnection.Offline
                    ConnectionStatus.KICKED_OFFLINE_BY_OTHER_CLIENT -> {
                        connectRequested = false
                        ChatConnection.SignedInElsewhere
                    }
                    ConnectionStatus.TOKEN_INCORRECT -> {
                        refreshTokenAndReconnect()
                        ChatConnection.Reconnecting
                    }
                    ConnectionStatus.TIMEOUT, ConnectionStatus.CONN_USER_BLOCKED, ConnectionStatus.PROXY_UNAVAILABLE -> {
                        connectRequested = false
                        ChatConnection.Failed
                    }
                    ConnectionStatus.SIGNED_OUT, ConnectionStatus.UNCONNECTED, ConnectionStatus.USER_ABANDON -> ChatConnection.Idle
                    else -> _connection.value
                }
                if (event.status == ConnectionStatus.CONNECTED) refreshUnread()
            }
        })

        NCEngine.addMessageHandler(HANDLER_ID, object : MessageHandler {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                _events.tryEmit(ChatEvent.Received(event.message))
                refreshUnread()
            }

            override fun onMessagesUpdated(event: MessagesUpdatedEvent) {
                _events.tryEmit(ChatEvent.Updated(event.messages))
            }

            override fun onMessageMetadataUpdated(event: MessageMetadataUpdatedEvent) {
                val message = event.message ?: return
                _events.tryEmit(ChatEvent.Metadata(message, event.metadata))
            }

            override fun onMessageReceiptResponse(event: MessageReceiptResponseEvent) {
                val ids = event.responses.filter { it.readCount > 0 }.mapNotNull { it.messageId }.toSet()
                if (ids.isNotEmpty()) _events.tryEmit(ChatEvent.Receipts(ids))
            }

            override fun onMessageDeleted(event: MessageDeletedEvent) {
                _events.tryEmit(ChatEvent.Deleted(event.messages.orEmpty()))
            }
        })

        NCEngine.addChannelHandler(HANDLER_ID, object : ChannelHandler {
            override fun onTypingStatusChanged(event: TypingStatusChangedEvent) {
                val identifier = event.channelIdentifier
                if (identifier.channelType != ChannelType.DIRECT) return
                val typing = event.userTypingStatus.any { it.userId == identifier.channelId }
                _events.tryEmit(ChatEvent.Typing(identifier.channelId, typing))
            }
        })
    }

    fun refreshUnread() {
        if (!initialized) return
        BaseChannel.getTotalUnreadCount(object : OperationHandler<Int> {
            override fun onResult(result: Int?, error: NCError?) {
                if (result != null) _totalUnread.value = result
            }
        })
    }

    // ---- Queries ---------------------------------------------------------------------------

    suspend fun conversations(): List<Conversation> {
        if (!initialized) return emptyList()
        val query = BaseChannel.createChannelsQuery(
            ChannelsQueryParams(listOf(ChannelType.DIRECT)).apply { pageSize = 50 },
        )
        val page = suspendCancellableCoroutine { cont ->
            query.loadNextPage(object : OperationHandler<ai.nexconn.chat.model.PageData<BaseChannel>> {
                override fun onResult(result: ai.nexconn.chat.model.PageData<BaseChannel>?, error: NCError?) {
                    if (error != null) cont.resumeWithException(ChatException(error.code, error.message ?: "Could not load chats"))
                    else cont.resume(result?.data.orEmpty())
                }
            })
        }
        return page.map { channel ->
            val last = channel.latestMessage
            Conversation(
                peerId = channel.channelId,
                unread = channel.unreadCount,
                preview = last?.let { preview(it) } ?: "",
                time = last?.sentTime ?: channel.operationTime,
                lastFromMe = last?.direction == MessageDirection.SEND,
            )
        }.sortedByDescending { it.time }
    }

    fun historyQuery(peerId: String, pageSize: Int = 30): MessagesQuery =
        BaseChannel.createMessagesQuery(
            MessagesQueryParams(ChannelIdentifier(ChannelType.DIRECT, peerId)).apply {
                this.pageSize = pageSize
                isAscending = false
            },
        )

    suspend fun loadPage(query: MessagesQuery): List<Message> = suspendCancellableCoroutine { cont ->
        query.loadNextPage(object : OperationHandler<ai.nexconn.chat.model.PageData<Message>> {
            override fun onResult(result: ai.nexconn.chat.model.PageData<Message>?, error: NCError?) {
                if (error != null) cont.resumeWithException(ChatException(error.code, error.message ?: "Could not load messages"))
                else cont.resume(result?.data.orEmpty())
            }
        })
    }

    suspend fun messageById(messageId: String): Message? = suspendCancellableCoroutine { cont ->
        BaseChannel.getMessageById(GetMessageByIdParams(messageId = messageId), object : OperationHandler<Message> {
            override fun onResult(result: Message?, error: NCError?) {
                cont.resume(result)
            }
        })
    }

    /** Ids among `messageIds` that the peer has read, for the read ticks on my messages. */
    suspend fun readMessageIds(peerId: String, messageIds: List<String>): Set<String> {
        if (messageIds.isEmpty()) return emptySet()
        return suspendCancellableCoroutine { cont ->
            DirectChannel(peerId).getMessageReadReceiptInfo(messageIds, object : OperationHandler<List<ai.nexconn.chat.message.model.ReadReceiptInfo>> {
                override fun onResult(result: List<ai.nexconn.chat.message.model.ReadReceiptInfo>?, error: NCError?) {
                    cont.resume(result.orEmpty().filter { it.readCount > 0 }.mapNotNull { it.messageId }.toSet())
                }
            })
        }
    }

    // ---- Actions ---------------------------------------------------------------------------

    /** Clears the unread badge and sends read receipts for messages the user has now seen. */
    fun markRead(peerId: String, visible: List<Message>) {
        if (!initialized) return
        val channel = DirectChannel(peerId)
        channel.clearUnreadCount(object : OperationHandler<Boolean> {
            override fun onResult(result: Boolean?, error: NCError?) = refreshUnread()
        })
        val ids = visible
            .filter { it.direction == MessageDirection.RECEIVE && it.needReceipt && !it.sentReceipt }
            .mapNotNull { it.messageId }
        if (ids.isNotEmpty()) {
            channel.sendReadReceiptResponse(ids, object : ErrorHandler {
                override fun onError(error: NCError?) {
                    if (error != null) Log.w(TAG, "read receipt failed ${error.code}")
                }
            })
        }
    }

    fun sendTyping(peerId: String) {
        if (_connection.value == ChatConnection.Connected) DirectChannel(peerId).sendTypingStatus("RC:TxtMsg")
    }

    fun sendText(peerId: String, text: String) {
        val params = SendMessageParams(TextMessage(text)).apply {
            needReceipt = true
            pushConfig = PushConfig().apply { pushContent = text.take(80) }
        }
        DirectChannel(peerId).sendMessage(params, sendHandler(peerId))
    }

    /**
     * Shares a Nexconn location message (RC:LBSMsg, with the required JPEG preview). SDK 26.4.2 rejects
     * client-side LocationMessage sends with an undocumented 31610, so our server sends the same built-in
     * type through the Platform Chat API as this user; both phones receive it like any other message.
     */
    suspend fun sendLocation(peerId: String, lat: Double, lng: Double, place: String) {
        apiCall {
            api.shareLocation(ShareLocationRequest(peerId, lat, lng, place.take(190), LocationThumbnail.base64(lat, lng)))
        }
    }

    fun sendImage(peerId: String, file: File) {
        val content = ImageMessage().apply {
            localPath = "file://${file.absolutePath}"
            original = false
        }
        sendMedia(peerId, content, "Sent a photo")
    }

    /** HD voice message: AAC recorded by [app.localcart.media.VoiceRecorder]. */
    fun sendVoice(peerId: String, file: File, durationSeconds: Int) {
        val content = HDVoiceMessage().apply {
            localPath = "file://${file.absolutePath}"
            duration = durationSeconds.coerceAtLeast(1)
        }
        sendMedia(peerId, content, "Sent a voice note")
    }

    private fun sendMedia(peerId: String, content: ai.nexconn.chat.message.MediaMessageContent, push: String) {
        val params = SendMediaMessageParams(content).apply {
            needReceipt = true
            pushConfig = PushConfig().apply { pushContent = push }
        }
        DirectChannel(peerId).sendMediaMessage(params, mediaHandler(peerId))
    }

    /** Retry a failed send: drop the failed local copy and send the same content again. */
    fun resend(message: Message) {
        val peerId = message.channelIdentifier.channelId
        val content = message.content ?: return
        val channel = DirectChannel(peerId)
        channel.deleteMessagesForMe(listOf(message), object : ErrorHandler {
            override fun onError(error: NCError?) = Unit
        })
        _events.tryEmit(ChatEvent.Deleted(listOf(message)))
        when (content) {
            is TextMessage -> sendText(peerId, content.text)
            is LocationMessage -> scope.launch { runCatching { sendLocation(peerId, content.latitude, content.longitude, content.poi ?: "Shared location") } }
            is ai.nexconn.chat.message.MediaMessageContent -> sendMedia(peerId, content, "Sent a message")
            else -> channel.sendMessage(SendMessageParams(content), sendHandler(peerId))
        }
    }

    private fun sendHandler(peerId: String) = object : SendMessageHandler {
        override fun onAttached(message: Message) {
            _events.tryEmit(ChatEvent.Attached(message))
        }

        override fun onResult(message: Message?, error: NCError?) {
            if (message == null) {
                if (error != null) _events.tryEmit(ChatEvent.SendError(peerId, sendError(error)))
                return
            }
            if (error == null) _events.tryEmit(ChatEvent.Sent(message))
            else _events.tryEmit(ChatEvent.Failed(message, sendError(error)))
        }
    }

    private fun mediaHandler(peerId: String) = object : SendMediaMessageHandler {
        override fun onAttached(message: Message) {
            _events.tryEmit(ChatEvent.Attached(message))
        }

        override fun onProgress(message: Message, progress: Int) {
            _events.tryEmit(ChatEvent.Progress(message, progress))
        }

        override fun onCanceled(message: Message) {
            _events.tryEmit(ChatEvent.Failed(message, "Upload cancelled"))
        }

        override fun onResult(message: Message?, error: NCError?) {
            if (message == null) {
                if (error != null) _events.tryEmit(ChatEvent.SendError(peerId, sendError(error)))
                return
            }
            if (error == null) _events.tryEmit(ChatEvent.Sent(message))
            else _events.tryEmit(ChatEvent.Failed(message, sendError(error)))
        }
    }

    private fun sendError(error: NCError): String = when (error.code) {
        20604 -> "Sending too fast. Wait a moment and retry."
        else -> if (_connection.value != ChatConnection.Connected) "Not sent. You're offline." else "Not sent (error ${error.code})."
    }

    companion object {
        private const val TAG = "NexconnChat"
        private const val HANDLER_ID = "localcart"

        /** One-line preview for conversation lists and notifications. */
        fun preview(message: Message): String = preview(message.content)

        fun preview(content: MessageContent?): String = when (content) {
            is TextMessage -> content.text
            is ImageMessage -> "Photo"
            is HDVoiceMessage -> "Voice note (${content.duration}s)"
            is LocationMessage -> "Location: ${content.poi?.takeIf { it.isNotBlank() } ?: "shared"}"
            is CartCardMessage -> "Cart #${content.code} with ${content.items.size} items"
            is BillCardMessage -> "Bill #${content.code} for ${Money.format(content.totalPaise)}"
            is StatusCardMessage -> "Order #${content.code} update"
            null -> ""
            else -> "Message"
        }
    }
}
