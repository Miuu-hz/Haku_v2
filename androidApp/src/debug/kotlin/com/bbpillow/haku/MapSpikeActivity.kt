package com.bbpillow.haku

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

/**
 * Debug-only render check: does an offline map draw Thai labels correctly on the real device?
 * adb push thailand.map /sdcard/Android/data/com.bbpillow.haku/files/map/thailand.map
 * adb shell am start -n com.bbpillow.haku/.MapSpikeActivity --ef lat 13.7649 --ef lon 100.5383 --ei zoom 16
 */
class MapSpikeActivity : Activity() {
  private var view: MapView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AndroidGraphicFactory.createInstance(application)
    val file = File(getExternalFilesDir("map"), "thailand.map")
    if (!file.exists()) {
      Log.e(TAG, "map file missing")
      finish()
      return
    }
    val startMs = System.currentTimeMillis()
    val map = MapView(this).also { view = it }
    setContentView(map)
    val cache = AndroidUtil.createTileCache(this, "map-spike", map.model.displayModel.tileSize, 1f, map.model.frameBufferModel.overdrawFactor)
    val layer = TileRendererLayer(cache, MapFile(file, "th"), map.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
    layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
    map.layerManager.layers.add(layer)
    map.setCenter(LatLong(intent.getFloatExtra("lat", 13.7649f).toDouble(), intent.getFloatExtra("lon", 100.5383f).toDouble()))
    map.setZoomLevel(intent.getIntExtra("zoom", 16).toByte())
    Log.i(TAG, "opened in ${System.currentTimeMillis() - startMs} ms")
  }

  override fun onDestroy() {
    view?.destroyAll()
    AndroidGraphicFactory.clearResourceMemoryCache()
    super.onDestroy()
  }

  private companion object {
    const val TAG = "HakuMapSpike"
  }
}
