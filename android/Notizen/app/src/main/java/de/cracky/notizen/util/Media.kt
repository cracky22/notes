package de.cracky.notizen.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException

data class PickedFile(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val isPdf: Boolean get() = mime == "application/pdf" || name.lowercase().endsWith(".pdf")
    val isImage: Boolean get() = mime.startsWith("image/")
}

object Media {
    private const val MAX_SIDE = 2560
    private val SERVER_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
    private const val RAW_IMAGE_LIMIT = 6L * 1024 * 1024

    fun describe(context: Context, uri: Uri): PickedFile {
        var name = "Datei"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == "Datei") uri.lastPathSegment?.let { name = it.substringAfterLast('/') }
        val mime = context.contentResolver.getType(uri) ?: when {
            name.lowercase().endsWith(".pdf") -> "application/pdf"
            name.lowercase().endsWith(".png") -> "image/png"
            name.lowercase().endsWith(".webp") -> "image/webp"
            name.lowercase().endsWith(".gif") -> "image/gif"
            name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
        return PickedFile(uri, name, mime, size)
    }

    /** Streamt die Datei direkt aus dem ContentResolver – auch 40-MB-PDFs landen nie komplett im RAM. */
    fun streamBody(context: Context, file: PickedFile): RequestBody {
        val type = file.mime.toMediaType()
        val resolver = context.contentResolver
        return object : RequestBody() {
            override fun contentType(): MediaType = type
            override fun contentLength(): Long = file.size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(file.uri) ?: throw IOException("Datei kann nicht gelesen werden")
                input.source().use { sink.writeAll(it) }
            }
        }
    }

    /**
     * Bilder: Formate, die der Server direkt versteht und die klein genug sind, gehen unverändert hoch
     * (der Server entfernt EXIF/GPS). Alles andere (HEIC, riesige Kamerafotos) wird auf dem Gerät
     * gedreht, auf max. 2560 px verkleinert und als JPEG kodiert – spart mobile Daten.
     */
    fun imageBody(context: Context, file: PickedFile): RequestBody {
        if (file.mime in SERVER_IMAGE_TYPES && file.size in 1..RAW_IMAGE_LIMIT) return streamBody(context, file)
        val jpeg = toJpeg(context, file.uri, MAX_SIDE, 88)
        return jpeg.toRequestBody("image/jpeg".toMediaType())
    }

    fun avatarBody(context: Context, uri: Uri): RequestBody =
        toJpeg(context, uri, 768, 90).toRequestBody("image/jpeg".toMediaType())

    fun toJpeg(context: Context, uri: Uri, maxSide: Int, quality: Int): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Bildformat wird nicht unterstützt")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Bild konnte nicht gelesen werden")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation != 0 || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.GERMANY, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
