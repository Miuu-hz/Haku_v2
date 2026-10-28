package com.bbpillow.haku.platform

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bbpillow.haku.network.DownloadResume
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.settings.InstalledModelRecord
import com.bbpillow.haku.surface.settings.ModelManagerPresenter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copy state for a user-imported `.litertlm` file. */
data class ImportState(
  val fileName: String,
  val fraction: Float,
  val done: Boolean = false,
  val error: String? = null,
)

/**
 * Copies a user-selected `.litertlm` document from Android SAF (`OpenDocument`) into
 * `<getExternalFilesDir("models")>/imports/<name>` via `<name>.part` then rename.
 *
 * ponytail: same `.part` + rename pattern as [ModelDownloader]; no background service needed for a local copy.
 */
class ModelImporter(
  private val app: Application,
  modelsDir: File,
  private val scope: CoroutineScope,
) {
  private val importsDir = File(modelsDir, "imports")
  private val _state = MutableStateFlow<ImportState?>(null)
  val state: StateFlow<ImportState?> = _state.asStateFlow()
  private var job: Job? = null

  fun file(fileName: String): File? =
    if (ActiveModel.isSafeFileName(fileName)) File(importsDir, fileName) else null

  fun listInstalled(): List<InstalledModelRecord> =
    importsDir.listFiles()
      ?.filter {
        it.isFile &&
          it.name.endsWith(ModelManagerPresenter.LITERTLM_SUFFIX, ignoreCase = true) &&
          ActiveModel.isSafeFileName(it.name) &&
          it.length() > 0L
      }
      ?.sortedBy { it.name.lowercase() }
      ?.map { InstalledModelRecord(it.name, it.length(), imported = true) }
      ?: emptyList()

  /** Starts copying [uri] on [Dispatchers.IO]. Validates `.litertlm` suffix and free space before writing. */
  fun import(uri: Uri, onComplete: (String) -> Unit = {}) {
    if (job?.isActive == true && !job!!.isCancelled) return
    job = scope.launch(Dispatchers.IO) {
      val meta = queryMeta(uri)
      val name = meta?.first?.trim().orEmpty()
      val stem = name.dropLast(ModelManagerPresenter.LITERTLM_SUFFIX.length)
      if (
        !name.endsWith(ModelManagerPresenter.LITERTLM_SUFFIX, ignoreCase = true) ||
        stem.isBlank() ||
        !ActiveModel.isSafeFileName(name)
      ) {
        _state.value = ImportState(name, 0f, done = false, error = ModelManagerPresenter.INVALID_IMPORT_TEXT)
        return@launch
      }
      val docSize = meta?.second ?: -1L
      _state.value = ImportState(name, 0f, done = false, error = null)
      val part = File(importsDir, "$name.part")
      val out = File(importsDir, name)
      try {
        importsDir.mkdirs()
        if (docSize > 0L && importsDir.usableSpace < docSize) {
          throw IOException("พื้นที่ไม่พอ ต้องว่างอีก ${docSize / 1_000_000_000 + 1} GB")
        }
        part.delete()
        var written = 0L
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("เปิดไฟล์ไม่สำเร็จ")
        input.use { stream ->
          FileOutputStream(part).use { output ->
            val buf = ByteArray(BUFFER)
            var lastReported = -1
            while (true) {
              coroutineContext.ensureActive()
              val n = stream.read(buf)
              if (n < 0) break
              output.write(buf, 0, n)
              written += n
              if (docSize > 0L) {
                val fraction = DownloadResume.fraction(written, docSize)
                val permille = (fraction * 1000).toInt()
                if (permille != lastReported) {
                  lastReported = permille
                  _state.value = ImportState(name, fraction, done = false)
                }
              }
            }
          }
        }
        coroutineContext.ensureActive()
        if (written <= 0L || (docSize > 0L && written != docSize)) throw IOException("ไฟล์ไม่ครบ")
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) throw IOException("rename failed")
        _state.value = ImportState(name, 1f, done = true)
        onComplete(name)
      } catch (e: CancellationException) {
        part.delete()
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "model import failed", e)
        part.delete()
        _state.value = ImportState(name, 0f, done = false, error = e.message ?: e.javaClass.simpleName)
      }
    }
  }

  /** Deletes an imported model file and any leftover `.part` file. */
  suspend fun delete(fileName: String) {
    if (!ActiveModel.isSafeFileName(fileName)) return
    if (_state.value?.fileName == fileName) {
      val active = job
      job = null
      active?.cancelAndJoin()
    }
    withContext(Dispatchers.IO) {
      File(importsDir, fileName).delete()
      File(importsDir, "$fileName.part").delete()
    }
    if (_state.value?.fileName == fileName) {
      _state.value = null
    }
  }

  fun clearError() {
    if (_state.value?.error != null) _state.value = null
  }

  private fun queryMeta(uri: Uri): Pair<String?, Long>? =
    runCatching {
      app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
        name to size
      }
    }.getOrNull()

  private companion object {
    const val TAG = "HakuModelImport"
    const val BUFFER = 256 * 1024
  }
}
