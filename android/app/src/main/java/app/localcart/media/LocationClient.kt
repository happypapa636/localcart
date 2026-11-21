package app.localcart.media

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class Place(val lat: Double, val lng: Double, val address: String?)

/** Device location via the platform LocationManager (no Play Services dependency). */
class LocationClient(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    /** Current fix, falling back to the freshest last-known fix. Caller must hold location permission. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val fix = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, Executors.newSingleThreadExecutor()) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
            if (fix != null) return fix
        }
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Best-effort street address for a point; null when the geocoder has nothing. */
    suspend fun address(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.forLanguageTag("en-IN"))
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine<String?> { cont ->
                        geocoder.getFromLocation(lat, lng, 1) { list -> cont.resume(list.firstOrNull()?.getAddressLine(0)) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
            }
        }.getOrNull()
    }

    /** Typed address to coordinates, for people who prefer not to share GPS. */
    suspend fun lookup(query: String): Place? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.forLanguageTag("en-IN"))
        runCatching {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<android.location.Address?> { cont ->
                        geocoder.getFromLocationName(query, 1) { list -> cont.resume(list.firstOrNull()) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)?.firstOrNull()
            }
            result?.let { Place(it.latitude, it.longitude, it.getAddressLine(0) ?: query) }
        }.getOrNull()
    }
}
