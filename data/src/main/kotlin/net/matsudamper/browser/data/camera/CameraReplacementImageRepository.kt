package net.matsudamper.browser.data.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * サイトがカメラを要求したときに、カメラ映像の代わりに返す画像を保存するリポジトリ。
 * 画像はアプリ全体で 1 枚を共有する。
 */
class CameraReplacementImageRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val imageFile = File(applicationContext.filesDir, "camera_replacement/image.jpg")

    private val _imageVersion = MutableStateFlow(if (imageFile.exists()) imageFile.lastModified() else null)

    /**
     * 保存済み画像の更新を示す値。画像が無い場合は null。
     * 画像を差し替えると値が変わるため、サムネイルの再読み込みのキーに使える
     */
    val imageVersion: StateFlow<Long?> = _imageVersion.asStateFlow()

    /** 保存済み画像のファイル。画像が無い場合は null */
    fun imageFileOrNull(): File? = imageFile.takeIf { it.exists() }

    /**
     * 選択された画像を縮小・JPEG 化して保存する。
     * ページへは data URL で渡すため、大きな画像はそのまま保存しない。
     */
    suspend fun save(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // ハードウェアビットマップは compress できないためソフトウェアで展開する
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longSide = max(info.size.width, info.size.height)
                    if (longSide > MAX_LONG_SIDE_PX) {
                        val scale = MAX_LONG_SIDE_PX.toFloat() / longSide
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
                imageFile.parentFile?.mkdirs()
                val tempFile = File(imageFile.parentFile, "image.jpg.tmp")
                tempFile.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
                bitmap.recycle()
                check(tempFile.renameTo(imageFile))
                _imageVersion.value = imageFile.lastModified()
            }.isSuccess
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            imageFile.delete()
            _imageVersion.value = null
        }
    }

    /** 保存済み画像を data URL で返す。画像が無い場合は null */
    suspend fun loadDataUrl(): String? {
        return withContext(Dispatchers.IO) {
            val file = imageFileOrNull() ?: return@withContext null
            runCatching {
                "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }
    }

    private companion object {
        const val MAX_LONG_SIDE_PX = 1280
        const val JPEG_QUALITY = 90
    }
}
