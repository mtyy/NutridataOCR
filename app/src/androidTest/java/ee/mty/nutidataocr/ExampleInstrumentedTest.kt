package ee.mty.nutidataocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
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
    fun checklistShowsStableFieldsAndDiagnosticsStayCollapsedUntilRequested() {
        compose.onNodeWithText("0 / 6 fields ready").assertIsDisplayed()
        compose.onNodeWithText("Raw text").assertDoesNotExist()
        compose.onNodeWithText("Pasted reply").assertDoesNotExist()
        REQUIRED_SCAN_NUTRIENTS.forEach { nutrient ->
            compose.onNodeWithTag("scan-${nutrient.name}").assertTextContains("Not found")
        }
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity)[OcrViewModel::class.java]
            val timestamp = SystemClock.elapsedRealtime()
            val lines = listOf("Fat 8 g", "Saturates 2 g", "Carbohydrates 12 g", "Sugars 0 g", "Protein 6 g", "Salt 0.5 g")
                .map { OcrLine(it, confidence = 0.95f) }
            repeat(3) { index -> model.onTextRecognized(lines, timestamp + index * 300L) }
        }
        compose.onNodeWithText("All 6 fields ready").assertIsDisplayed()
        REQUIRED_SCAN_NUTRIENTS.forEach { nutrient ->
            compose.onNodeWithTag("scan-${nutrient.name}").assertTextContains("Stable")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("All 6 fields ready").assertIsDisplayed()
        compose.onNodeWithText("Scan diagnostics").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        compose.onNodeWithText("Raw text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scan diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Raw text").assertDoesNotExist()
        compose.onNodeWithText("New scan").performClick()
        compose.onNodeWithText("0 / 6 fields ready").assertIsDisplayed()
        compose.onNodeWithText("All 6 fields ready").assertDoesNotExist()
    }

    @Test
    fun manualValueCanBeEditedCancelledRestoredToOcrAndReset() {
        compose.onNodeWithContentDescription("Edit Salt").performScrollTo().performClick()
        compose.onNodeWithText("Amount (g)").performTextReplacement("0,75")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Amount (g)").assertTextContains("0,75")
        compose.onNodeWithText("Save value").performClick()
        compose.onNodeWithTag("scan-SALT").assertTextContains("0.75 g").assertTextContains("Manual")
        compose.onNodeWithText("1 / 6 fields ready").assertIsDisplayed()

        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity)[OcrViewModel::class.java]
            val timestamp = SystemClock.elapsedRealtime()
            repeat(3) { index ->
                model.onTextRecognized(listOf(OcrLine("Salt 1 g", confidence = 0.95f)), timestamp + index * 300L)
            }
        }
        compose.onNodeWithTag("scan-SALT").assertTextContains("0.75 g")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("scan-SALT").assertTextContains("0.75 g").assertTextContains("Manual")
        compose.onNodeWithContentDescription("Edit Salt").performScrollTo().performClick()
        compose.onNodeWithText("Amount (g)").performTextReplacement("-1")
        compose.onNodeWithText("Save value").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.scan_invalid_amount)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("scan-SALT").assertTextContains("0.75 g")
        compose.onNodeWithContentDescription("Edit Salt").performScrollTo().performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithTag("scan-SALT").assertTextContains("1 g").assertTextContains("Stable")
        compose.onNodeWithContentDescription("Edit Salt").performScrollTo().performClick()
        compose.onNodeWithText("Amount (g)").performTextReplacement("0")
        compose.onNodeWithText("Save value").performClick()
        compose.onNodeWithTag("scan-SALT").assertTextContains("0 g").assertTextContains("Manual")
        compose.onNodeWithText("New scan").performClick()
        compose.onNodeWithTag("scan-SALT").assertTextContains("Not found")
        compose.onNodeWithText("0 / 6 fields ready").assertIsDisplayed()
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
        compose.onNodeWithText("Gemini response").performScrollTo().performClick()
        compose.onNodeWithText("Paste and import").performScrollTo().performClick()
        compose.onNodeWithText("fat: <0.5 g").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("fat: <0.5 g").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pasted reply").performScrollTo().performTextReplacement("Not JSON")
        compose.onNodeWithText("Import", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.gemini_invalid_response))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("fat: <0.5 g").assertExists()
        compose.onNodeWithText("New scan").performClick()
        compose.onNodeWithText("fat: <0.5 g").assertDoesNotExist()
    }
}