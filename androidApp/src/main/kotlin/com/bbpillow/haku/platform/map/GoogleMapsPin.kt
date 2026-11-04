package com.bbpillow.haku.platform.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

object GoogleMapsPin {
  /** Only ever called from a tap. Sends the coordinates and nothing else: never a place name. */
  fun open(context: Context, lat: Double, lon: Double) {
    val query = String.format(Locale.US, "%s,%s", lat, lon)
    try {
      val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")).apply {
        setPackage("com.google.android.apps.maps")
      }
      context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
      try {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query"))
        context.startActivity(webIntent)
      } catch (_: Exception) {
        Toast.makeText(context, "เปิดแผนที่ไม่ได้", Toast.LENGTH_SHORT).show()
      }
    }
  }
}
