package ee.mty.nutidataocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<OcrActivity>()

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("ee.mty.nutidataocr", appContext.packageName)
    }

    @Test
    fun sharedPhotoHasReadableUriPromptAndNarrowProviderScope() {
        val context = compose.activity
        val photo = createGeminiPhotoFile(context)
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val intent = geminiShareIntent(context, photo)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("image/jpeg", intent.type)
            assertEquals("content", uri.scheme)
            assertEquals(GEMINI_NUTRITION_PROMPT, intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openInputStream(uri)!!.use {
                assertArrayEquals(photo.readBytes(), it.readBytes())
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.photos", context.filesDir.resolve("private.txt"))
            }
        } finally {
            bitmap.recycle()
            photo.delete()
        }
    }

    @Test
    fun pastedConversationImportsSurvivesRecreationAndResets() {
        compose.runOnUiThread {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Test reply", """
                    Here are the values:
                    ```json
                    {"columns":[{"basis":"per 100 g","nutrients":[{"name":"fat","amount":"<0.5","unit":"g"}]}],"uncertain":[]}
                    ```
                    Please check the label.
                """.trimIndent())
            )
        }
        compose.onNodeWithText("Paste and import").performScrollTo().performClick()
        compose.onNodeWithText("fat: <0.5 g").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("fat: <0.5 g").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pasted reply").performScrollTo().performTextReplacement("Not JSON")
        compose.onNodeWithText("Import", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.gemini_invalid_response))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("fat: <0.5 g").assertExists()
        compose.onNodeWithText("New scan").performScrollTo().performClick()
        compose.onNodeWithText("fat: <0.5 g").assertDoesNotExist()
    }
}