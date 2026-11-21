package app.localcart.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** LocalCart backend (server/src/routes). Every call except auth and config needs a session. */
interface LocalCartApi {
    @GET("api/config") suspend fun config(): ConfigDto

    @POST("api/auth/signup") suspend fun signup(@Body body: SignupRequest): AuthResponse
    @POST("api/auth/login") suspend fun login(@Body body: LoginRequest): AuthResponse
    @GET("api/me") suspend fun me(): UserResponse
    @PATCH("api/me") suspend fun updateMe(@Body body: UpdateMeRequest): UserResponse

    @POST("api/chat/token") suspend fun chatToken(@Body body: TokenRequest): ChatTokenDto
    @POST("api/chats") suspend fun openChat(@Body body: OpenChatRequest): PeerResponse
    @GET("api/chats") suspend fun peers(): PeersResponse
    @POST("api/chats/location") suspend fun shareLocation(@Body body: ShareLocationRequest): MessageIdResponse

    @Multipart
    @POST("api/uploads")
    suspend fun upload(@Part file: MultipartBody.Part, @Part("kind") kind: RequestBody): UploadResponse

    @POST("api/shops") suspend fun createShop(@Body body: ShopRequest): ShopResponse
    @GET("api/shops/mine") suspend fun myShop(): ShopResponse
    @PATCH("api/shops/mine") suspend fun updateShop(@Body body: ShopRequest): ShopResponse
    @PATCH("api/shops/mine") suspend fun setShopOpen(@Body body: OpenToggleRequest): ShopResponse

    @GET("api/shops/nearby")
    suspend fun nearby(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("category") category: String?,
        @Query("q") query: String?,
        @Query("radiusKm") radiusKm: Double = 10.0,
    ): ShopsResponse

    @GET("api/shops/{id}") suspend fun shop(@Path("id") id: String): ShopDetailResponse

    @GET("api/shops/mine/items") suspend fun myItems(): ItemsResponse
    @POST("api/shops/mine/items") suspend fun addItem(@Body body: ItemRequest): ItemResponse
    @POST("api/shops/mine/items/bulk") suspend fun addItems(@Body body: BulkItemsRequest): ItemsResponse
    @POST("api/shops/mine/items/parse") suspend fun parseList(@Body body: ParseListRequest): CatalogDraftResponse
    @PATCH("api/shops/mine/items/{id}") suspend fun updateItem(@Path("id") id: String, @Body body: ItemRequest): ItemResponse
    @PATCH("api/shops/mine/items/{id}") suspend fun setStock(@Path("id") id: String, @Body body: StockRequest): ItemResponse
    @DELETE("api/shops/mine/items/{id}") suspend fun deleteItem(@Path("id") id: String)

    @POST("api/orders/draft") suspend fun draftFromText(@Body body: DraftTextRequest): DraftResponse

    @Multipart
    @POST("api/orders/draft")
    suspend fun draftFromVoice(@Part("shopId") shopId: RequestBody, @Part audio: MultipartBody.Part): DraftResponse

    @GET("api/orders")
    suspend fun orders(@Query("scope") scope: String, @Query("peer") peer: String? = null): OrdersResponse

    @GET("api/orders/{id}") suspend fun order(@Path("id") id: String): OrderResponse
    @PUT("api/orders/{id}/cart") suspend fun editCart(@Path("id") id: String, @Body body: EditCartRequest): OrderResponse
    @POST("api/orders/{id}/confirm") suspend fun confirmCart(@Path("id") id: String): OrderResponse
    @POST("api/orders/{id}/bill") suspend fun sendBill(@Path("id") id: String, @Body body: BillRequest): OrderResponse
    @POST("api/orders/{id}/accept") suspend fun acceptBill(@Path("id") id: String, @Body body: AcceptRequest): OrderResponse
    @POST("api/orders/{id}/payment") suspend fun payment(@Path("id") id: String, @Body body: PaymentRequest): OrderResponse
    @POST("api/orders/{id}/status") suspend fun advance(@Path("id") id: String, @Body body: StatusRequest): OrderResponse
    @POST("api/orders/{id}/cancel") suspend fun cancel(@Path("id") id: String, @Body body: CancelRequest): OrderResponse
    @POST("api/orders/{id}/rate") suspend fun rate(@Path("id") id: String, @Body body: RateRequest): OrderResponse
    @POST("api/orders/{id}/reorder") suspend fun reorder(@Path("id") id: String): OrderResponse
    @PUT("api/orders/{id}/delivery-location") suspend fun deliveryLocation(@Path("id") id: String, @Body body: LocationRequest): OrderResponse

    @GET("api/dashboard") suspend fun dashboard(): DashboardDto
}
