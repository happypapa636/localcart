package app.localcart.data.repo

import app.localcart.data.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class AuthRepository(private val api: LocalCartApi, private val session: SessionRepository) {
    suspend fun signUp(name: String, phone: String, password: String, role: String) = apiCall {
        api.signup(SignupRequest(name.trim(), phone.trim(), password, role))
    }.also { session.signIn(it.token, it.user) }.user

    suspend fun signIn(phone: String, password: String) = apiCall {
        api.login(LoginRequest(phone.trim(), password))
    }.also { session.signIn(it.token, it.user) }.user

    suspend fun refreshMe(): UserDto = apiCall { api.me() }.user.also(session::updateUser)

    suspend fun updateMe(request: UpdateMeRequest): UserDto = apiCall { api.updateMe(request) }.user.also(session::updateUser)
}

class ShopRepository(private val api: LocalCartApi) {
    private val _myShop = MutableStateFlow<ShopDto?>(null)
    val myShop: StateFlow<ShopDto?> = _myShop.asStateFlow()

    suspend fun loadMyShop(): ShopDto? = apiCall { api.myShop() }.shop.also { _myShop.value = it }
    suspend fun create(request: ShopRequest): ShopDto = apiCall { api.createShop(request) }.shop!!.also { _myShop.value = it }
    suspend fun update(request: ShopRequest): ShopDto = apiCall { api.updateShop(request) }.shop!!.also { _myShop.value = it }
    suspend fun setOpen(open: Boolean): ShopDto = apiCall { api.setShopOpen(OpenToggleRequest(open)) }.shop!!.also { _myShop.value = it }

    suspend fun nearby(lat: Double, lng: Double, category: String?, query: String?) =
        apiCall { api.nearby(lat, lng, category, query?.takeIf { it.isNotBlank() }) }.shops

    suspend fun detail(id: String) = apiCall { api.shop(id) }

    suspend fun uploadPhoto(file: File, kind: String): String = apiCall {
        val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        api.upload(part, kind.toRequestBody("text/plain".toMediaType()))
    }.url

    fun clear() {
        _myShop.value = null
    }
}

class CatalogRepository(private val api: LocalCartApi) {
    private val _items = MutableStateFlow<List<CatalogItemDto>>(emptyList())
    val items: StateFlow<List<CatalogItemDto>> = _items.asStateFlow()

    suspend fun load(): List<CatalogItemDto> = apiCall { api.myItems() }.items.also { _items.value = it }

    suspend fun add(request: ItemRequest) = apiCall { api.addItem(request) }.item.also { item ->
        _items.update { (it + item).sortedBy { i -> i.name.lowercase() } }
    }

    suspend fun addAll(requests: List<ItemRequest>) = apiCall { api.addItems(BulkItemsRequest(requests)) }.items.also { added ->
        _items.update { (it + added).sortedBy { i -> i.name.lowercase() } }
    }

    suspend fun update(id: String, request: ItemRequest) = apiCall { api.updateItem(id, request) }.item.also(::replace)

    suspend fun setStock(id: String, inStock: Boolean) = apiCall { api.setStock(id, StockRequest(inStock)) }.item.also(::replace)

    suspend fun delete(id: String) {
        apiCall { api.deleteItem(id) }
        _items.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun parseList(text: String) = apiCall { api.parseList(ParseListRequest(text)) }.items

    private fun replace(item: CatalogItemDto) = _items.update { list -> list.map { if (it.id == item.id) item else it } }

    fun clear() {
        _items.value = emptyList()
    }
}

/**
 * Orders, with an in-memory cache keyed by id. Chat cards observe [orders] so an update made from
 * any screen (or arriving as a Nexconn metadata event) re-renders every card for that order.
 */
class OrderRepository(private val api: LocalCartApi) {
    private val _orders = MutableStateFlow<Map<String, OrderDto>>(emptyMap())
    val orders: StateFlow<Map<String, OrderDto>> = _orders.asStateFlow()

    private fun store(order: OrderDto): OrderDto {
        _orders.update { it + (order.id to order) }
        return order
    }

    suspend fun refresh(id: String): OrderDto = store(apiCall { api.order(id) }.order)
    suspend fun list(scope: String, peer: String? = null) = apiCall { api.orders(scope, peer) }.orders

    suspend fun draftFromText(shopId: String, text: String): DraftResponse =
        apiCall { api.draftFromText(DraftTextRequest(shopId, text)) }.also { store(it.order) }

    suspend fun draftFromVoice(shopId: String, audio: File): DraftResponse = apiCall {
        val part = MultipartBody.Part.createFormData("audio", audio.name, audio.asRequestBody("audio/aac".toMediaType()))
        api.draftFromVoice(shopId.toRequestBody("text/plain".toMediaType()), part)
    }.also { store(it.order) }

    suspend fun editCart(id: String, lines: List<CartLineRequest>) = store(apiCall { api.editCart(id, EditCartRequest(lines)) }.order)
    suspend fun confirm(id: String) = store(apiCall { api.confirmCart(id) }.order)
    suspend fun sendBill(id: String, request: BillRequest) = store(apiCall { api.sendBill(id, request) }.order)
    suspend fun accept(id: String, method: String) = store(apiCall { api.acceptBill(id, AcceptRequest(method)) }.order)
    suspend fun payment(id: String, action: String) = store(apiCall { api.payment(id, PaymentRequest(action)) }.order)
    suspend fun advance(id: String, status: String) = store(apiCall { api.advance(id, StatusRequest(status)) }.order)
    suspend fun cancel(id: String, reason: String) = store(apiCall { api.cancel(id, CancelRequest(reason)) }.order)
    suspend fun rate(id: String, stars: Int, comment: String?) = store(apiCall { api.rate(id, RateRequest(stars, comment)) }.order)
    suspend fun reorder(id: String) = store(apiCall { api.reorder(id) }.order)
    suspend fun setDeliveryLocation(id: String, lat: Double, lng: Double, address: String?) =
        store(apiCall { api.deliveryLocation(id, LocationRequest(lat, lng, address)) }.order)

    suspend fun dashboard() = apiCall { api.dashboard() }

    fun clear() {
        _orders.value = emptyMap()
    }
}

/**
 * Names and photos for chat peers. Nexconn stores no app profiles, so the conversation list resolves
 * Nexconn user ids through the backend's chat relationships.
 */
class PeerDirectory(private val api: LocalCartApi) {
    private val _peers = MutableStateFlow<Map<String, PeerDto>>(emptyMap())
    val peers: StateFlow<Map<String, PeerDto>> = _peers.asStateFlow()

    suspend fun refresh(): Map<String, PeerDto> {
        val list = apiCall { api.peers() }.peers
        _peers.update { current -> current + list.associateBy { it.nexconnId } }
        return _peers.value
    }

    suspend fun openWithShop(shopId: String): PeerDto =
        apiCall { api.openChat(OpenChatRequest(shopId)) }.peer.also { peer -> _peers.update { it + (peer.nexconnId to peer) } }

    fun get(nexconnId: String): PeerDto? = _peers.value[nexconnId]

    fun clear() {
        _peers.value = emptyMap()
    }
}
