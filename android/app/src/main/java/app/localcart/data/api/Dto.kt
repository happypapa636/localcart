package app.localcart.data.api

import kotlinx.serialization.Serializable

// ---- Responses -------------------------------------------------------------------------------

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val phone: String,
    val role: String,
    val nexconnId: String,
    val addressText: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    val isShopkeeper: Boolean get() = role == "shopkeeper"
    val hasLocation: Boolean get() = lat != null && lng != null
}

@Serializable data class AuthResponse(val token: String, val user: UserDto)
@Serializable data class UserResponse(val user: UserDto)
@Serializable data class ConfigDto(val nexconnAppKey: String, val areaCode: String = "SG", val idPrefix: String = "")
@Serializable data class ChatTokenDto(val userId: String, val token: String, val appKey: String)

@Serializable
data class ShopDto(
    val id: String,
    val name: String,
    val category: String,
    val description: String? = null,
    val photoUrl: String? = null,
    val phone: String,
    val addressText: String,
    val lat: Double,
    val lng: Double,
    val deliveryRadiusKm: Double,
    val opensAt: String,
    val closesAt: String,
    val isOpen: Boolean,
    val openNow: Boolean,
    val upiId: String? = null,
    val rating: Double? = null,
    val ratingCount: Int = 0,
    val ownerNexconnId: String? = null,
    val distanceKm: Double? = null,
    val deliversToYou: Boolean? = null,
)

@Serializable data class ShopResponse(val shop: ShopDto?)
@Serializable data class ShopsResponse(val shops: List<ShopDto>)

@Serializable
data class CatalogItemDto(
    val id: String,
    val name: String,
    val unit: String,
    val pricePaise: Int,
    val inStock: Boolean,
    val photoUrl: String? = null,
)

@Serializable data class ShopDetailResponse(val shop: ShopDto, val items: List<CatalogItemDto>)
@Serializable data class ItemsResponse(val items: List<CatalogItemDto>)
@Serializable data class ItemResponse(val item: CatalogItemDto)
@Serializable data class CatalogDraftItemDto(val name: String, val unit: String, val pricePaise: Int? = null)
@Serializable data class CatalogDraftResponse(val items: List<CatalogDraftItemDto>)
@Serializable data class UploadResponse(val url: String)

@Serializable
data class PeerDto(
    val nexconnId: String,
    val kind: String,
    val name: String,
    val photoUrl: String? = null,
    val shopId: String? = null,
    val customerId: String? = null,
    val activeOrders: Int = 0,
)

@Serializable data class PeerResponse(val peer: PeerDto)
@Serializable data class PeersResponse(val peers: List<PeerDto>)

@Serializable
data class OrderItemDto(
    val id: String,
    val catalogItemId: String? = null,
    val name: String,
    val unit: String,
    val quantity: Double,
    val unitPricePaise: Int? = null,
    val available: Boolean = true,
    val requestedText: String? = null,
    val lineTotalPaise: Int = 0,
)

@Serializable
data class OrderShopDto(
    val id: String,
    val name: String,
    val photoUrl: String? = null,
    val phone: String? = null,
    val upiId: String? = null,
    val nexconnId: String,
)

@Serializable
data class OrderCustomerDto(
    val id: String,
    val name: String,
    val nexconnId: String,
    val phone: String? = null,
    val addressText: String? = null,
)

@Serializable data class DeliveryDto(val lat: Double, val lng: Double, val address: String? = null)

@Serializable
data class OrderDto(
    val id: String,
    val code: String,
    val status: String,
    val source: String,
    val sourceText: String? = null,
    val viewerRole: String,
    val shop: OrderShopDto,
    val customer: OrderCustomerDto,
    val items: List<OrderItemDto>,
    val subtotalPaise: Int,
    val deliveryFeePaise: Int,
    val totalPaise: Int,
    val unpricedCount: Int = 0,
    val billNote: String? = null,
    val paymentMethod: String? = null,
    val paymentStatus: String = "none",
    val cancelReason: String? = null,
    val cancelledBy: String? = null,
    val delivery: DeliveryDto? = null,
    val cartMessageId: String? = null,
    val billMessageId: String? = null,
    val statusMessageId: String? = null,
    val cartRev: Int = 1,
    val rating: Int? = null,
    val ratingComment: String? = null,
    val upiLink: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    val isCustomerView: Boolean get() = viewerRole == "customer"
}

@Serializable data class OrderResponse(val order: OrderDto)

@Serializable data class DraftNoteDto(val name: String, val note: String? = null)

@Serializable
data class DraftResponse(
    val order: OrderDto,
    val transcript: String? = null,
    val language: String? = null,
    val notes: List<DraftNoteDto> = emptyList(),
)

@Serializable data class SummaryShopDto(val id: String, val name: String, val photoUrl: String? = null)
@Serializable data class SummaryCustomerDto(val id: String, val name: String)

@Serializable
data class OrderSummaryDto(
    val id: String,
    val code: String,
    val status: String,
    val shop: SummaryShopDto,
    val customer: SummaryCustomerDto,
    val totalPaise: Int,
    val itemCount: Int,
    val paymentMethod: String? = null,
    val paymentStatus: String = "none",
    val rating: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable data class OrdersResponse(val orders: List<OrderSummaryDto>)

@Serializable
data class DashboardDto(
    val shop: ShopDto? = null,
    val counts: Map<String, Int> = emptyMap(),
    val todaySalesPaise: Int = 0,
    val todayDelivered: Int = 0,
    val todayOrders: Int = 0,
    val pendingPayments: Int = 0,
)

@Serializable data class ErrorBody(val code: String = "error", val message: String = "Something went wrong.")
@Serializable data class ErrorEnvelope(val error: ErrorBody)

// ---- Requests --------------------------------------------------------------------------------

@Serializable data class SignupRequest(val name: String, val phone: String, val password: String, val role: String)
@Serializable data class LoginRequest(val phone: String, val password: String)
@Serializable data class UpdateMeRequest(val name: String? = null, val addressText: String? = null, val lat: Double? = null, val lng: Double? = null)
@Serializable data class TokenRequest(val refresh: Boolean = false)

@Serializable
data class ShopRequest(
    val name: String,
    val category: String,
    val description: String? = null,
    val photoUrl: String? = null,
    val phone: String,
    val addressText: String,
    val lat: Double,
    val lng: Double,
    val deliveryRadiusKm: Double,
    val opensAt: String,
    val closesAt: String,
    val upiId: String? = null,
)

@Serializable data class OpenToggleRequest(val isOpen: Boolean)

@Serializable
data class ItemRequest(
    val name: String,
    val unit: String,
    val pricePaise: Int,
    val inStock: Boolean,
    val photoUrl: String? = null,
)

@Serializable data class StockRequest(val inStock: Boolean)
@Serializable data class BulkItemsRequest(val items: List<ItemRequest>)
@Serializable data class ParseListRequest(val text: String)
@Serializable data class OpenChatRequest(val shopId: String)
@Serializable data class DraftTextRequest(val shopId: String, val text: String)

@Serializable
data class CartLineRequest(
    val catalogItemId: String?,
    val name: String,
    val unit: String,
    val quantity: Double,
    val requestedText: String? = null,
)

@Serializable data class EditCartRequest(val items: List<CartLineRequest>)
@Serializable data class BillLineRequest(val itemId: String, val available: Boolean, val quantity: Double, val unitPricePaise: Int)
@Serializable data class BillRequest(val lines: List<BillLineRequest>, val deliveryFeePaise: Int, val note: String? = null)
@Serializable data class AcceptRequest(val paymentMethod: String)
@Serializable data class PaymentRequest(val action: String)
@Serializable data class StatusRequest(val status: String)
@Serializable data class CancelRequest(val reason: String)
@Serializable data class RateRequest(val stars: Int, val comment: String? = null)
@Serializable data class LocationRequest(val lat: Double, val lng: Double, val address: String? = null)
@Serializable data class ShareLocationRequest(val peerNexconnId: String, val lat: Double, val lng: Double, val place: String, val thumbnail: String)
@Serializable data class MessageIdResponse(val messageId: String)
