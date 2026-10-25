package com.okoda.usual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun imageDraft(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val raw = File.createTempFile("picked-", ".raw", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            raw.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 20 * 1024 * 1024) throw UserFacingException("Choose a photo below 20 MiB.")
                    output.write(buffer, 0, count)
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) throw UserFacingException("This photo cannot be decoded safely.")
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val decoded = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UserFacingException("This photo could not be opened.")
        val orientation = try { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val result = File(context.cacheDir, "preview-" + UUID.randomUUID() + ".jpg")
        result.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (rotated !== decoded) decoded.recycle()
        rotated.recycle()
        if (result.length() > 4 * 1024 * 1024) { result.delete(); throw UserFacingException("Choose a smaller photo.") }
        result
    } finally { raw.delete() }
}
