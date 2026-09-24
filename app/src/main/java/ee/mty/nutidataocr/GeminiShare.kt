package ee.mty.nutidataocr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal fun createGeminiPhotoFile(context: Context): File {
    val directory = File(context.cacheDir, "gemini_photos")
    check(directory.isDirectory || directory.mkdirs()) { "Could not create photo cache" }
    val expiresBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    directory.listFiles()?.filter { it.lastModified() < expiresBefore }?.forEach { it.delete() }
    return File.createTempFile("nutrition_", ".jpg", directory)
}

internal fun geminiShareIntent(context: Context, photo: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", photo)
    return Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, GEMINI_NUTRITION_PROMPT)
        clipData = ClipData.newUri(context.contentResolver, "Nutrition photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun openGemini(context: Context, photo: File) {
    val intent = geminiShareIntent(context, photo)
    try {
        context.startActivity(Intent(intent).setPackage("com.google.android.apps.bard"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_nutrition_photo)))
    }
}