package app.localcart.data.api

import app.localcart.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A failure the UI can show as-is. `code` mirrors the server's stable error codes. */
class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message)

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

object ApiClient {
    fun create(tokenProvider: () -> String?, onUnauthorized: () -> Unit): Pair<LocalCartApi, OkHttpClient> {
        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val request = chain.request().newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }.build()
            val response = chain.proceed(request)
            if (response.code == 401 && token != null) onUnauthorized()
            response
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // AI parsing of a voice note can take a while on the free tier, with retries server-side.
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(auth)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(LocalCartApi::class.java) to client
    }
}

/** Runs an API call and maps every failure to an [ApiException] with a user-facing message. */
suspend fun <T> apiCall(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val parsed = body?.let { runCatching { AppJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
        throw ApiException(
            parsed?.error?.code ?: "http_${e.code()}",
            parsed?.error?.message ?: when (e.code()) {
                401 -> "Your session has expired. Sign in again."
                in 500..599 -> "LocalCart is having trouble right now. Try again in a moment."
                else -> "Request failed (${e.code()})."
            },
            e.code(),
        )
    } catch (e: SocketTimeoutException) {
        throw ApiException("timeout", "This is taking too long. Check your connection and try again.")
    } catch (e: IOException) {
        throw ApiException("offline", "No internet connection. Check your network and try again.")
    } catch (e: Exception) {
        throw ApiException("unexpected", e.message ?: "Something went wrong.")
    }
}
