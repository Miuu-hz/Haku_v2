package com.bbpillow.haku.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.bbpillow.haku.capture.GeocodedAddressInput
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reverse geocoding through the platform [Geocoder] (Google's service on Play devices: coordinates leave the phone, see
 * the privacy notice). No new dependency. Null = the service answered with no address; [IOException] = not reached.
 */
object PlaceGeocoder {
  private val THAI = Locale.forLanguageTag("th-TH")

  fun isPresent(): Boolean = Geocoder.isPresent()

  suspend fun lookup(context: Context, lat: Double, lon: Double): GeocodedAddressInput? {
    val geocoder = Geocoder(context, THAI)
    val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      suspendCancellableCoroutine { c ->
        geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
          override fun onGeocode(addresses: MutableList<Address>) = c.resume(addresses.firstOrNull())
          override fun onError(errorMessage: String?) = c.resumeWithException(IOException("geocoder error"))
        })
      }
    } else {
      @Suppress("DEPRECATION")
      withContext(Dispatchers.IO) { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }
    }
    return address?.let {
      GeocodedAddressInput(it.featureName, it.subThoroughfare, it.thoroughfare, it.subLocality, it.locality, it.subAdminArea, it.postalCode)
    }
  }
}
