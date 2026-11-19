package com.bandstudio.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Chia sẻ / lưu bản xuất. Bản xuất nằm trong bộ nhớ tạm của ứng dụng; chia sẻ qua FileProvider, lưu vào máy qua MediaStore. */
object ExportActions {

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Gửi bản nhạc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Lưu vào Music/BandStudio. Trả false nếu máy Android < 10 hoặc lỗi. */
    fun saveToDevice(ctx: Context, file: File, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/BandStudio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
