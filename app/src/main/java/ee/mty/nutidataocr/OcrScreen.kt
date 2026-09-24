package ee.mty.nutidataocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

@Composable
internal fun OcrScreen(model: OcrViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var resetAtMillis by rememberSaveable { mutableStateOf(Long.MIN_VALUE) }
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pastedResponse by rememberSaveable { mutableStateOf("") }
    var importedResponse by rememberSaveable { mutableStateOf("") }
    var responseError by rememberSaveable { mutableStateOf<Int?>(null) }
    val importedNutrition = remember(importedResponse) {
        runCatching { parseGeminiResponse(importedResponse) }.getOrNull()
    }
    fun importResponse(text: String) {
        if (text.length > 65_536) {
            responseError = R.string.gemini_response_too_long
            return
        }
        pastedResponse = text
        if (runCatching { parseGeminiResponse(text) }.isSuccess) {
            importedResponse = text
            responseError = null
        } else {
            responseError = R.string.gemini_invalid_response
        }
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val nutrients = model.nutrients
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    pendingPhotoPath?.let { photoPath ->
        fun dismissPhoto() {
            File(photoPath).delete()
            pendingPhotoPath = null
        }
        AlertDialog(
            onDismissRequest = ::dismissPhoto,
            title = { Text(stringResource(R.string.open_gemini)) },
            text = { Text(stringResource(R.string.gemini_external_notice)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        openGemini(context, File(photoPath))
                        pendingPhotoPath = null
                    } catch (exception: Exception) {
                        Log.e("GeminiShare", "Could not share photo", exception)
                        Toast.makeText(context, R.string.gemini_share_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.open_gemini)) }
            },
            dismissButton = {
                TextButton(onClick = ::dismissPhoto) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    if (!permissionGranted) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.allow_camera))
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            onTextRecognized = model::onTextRecognized,
            onGeminiPhoto = { photo, capturedAtMillis ->
                if (capturedAtMillis <= resetAtMillis) {
                    photo.delete()
                } else {
                    pendingPhotoPath?.let { File(it).delete() }
                    pendingPhotoPath = photo.absolutePath
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.nutrients),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = {
                    resetAtMillis = SystemClock.elapsedRealtime()
                    model.reset(resetAtMillis)
                    pastedResponse = ""
                    importedResponse = ""
                    responseError = null
                    pendingPhotoPath?.let { File(it).delete() }
                    pendingPhotoPath = null
                }) {
                    Text(stringResource(R.string.new_scan))
                }
            }
            if (nutrients.isEmpty()) {
                Text(stringResource(R.string.no_nutrients_detected))
            }
            nutrients.forEach { (nutrient, values) ->
                val label = stringResource(
                    when (nutrient) {
                        Nutrient.ENERGY_KJ, Nutrient.ENERGY_KCAL -> R.string.energy
                        Nutrient.FAT -> R.string.fat
                        Nutrient.SATURATES -> R.string.saturates
                        Nutrient.CARBOHYDRATES -> R.string.carbohydrates
                        Nutrient.SUGARS -> R.string.sugars
                        Nutrient.FIBRE -> R.string.fibre
                        Nutrient.PROTEIN -> R.string.protein
                        Nutrient.SALT -> R.string.salt
                    }
                )
                Text("$label: ${values.joinToString(" / ") { "${it.amount} ${it.unit}" }}")
            }
            Text(stringResource(R.string.gemini_response), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = pastedResponse,
                onValueChange = {
                    if (it.length <= 65_536) {
                        pastedResponse = it
                        responseError = null
                    } else responseError = R.string.gemini_response_too_long
                },
                label = { Text(stringResource(R.string.gemini_pasted_reply)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                isError = responseError != null,
            )
            responseError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    importResponse(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                }) { Text(stringResource(R.string.gemini_paste_import)) }
                TextButton(onClick = { importResponse(pastedResponse) }, enabled = pastedResponse.isNotBlank()) {
                    Text(stringResource(R.string.gemini_import))
                }
            }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Nutrition prompt", GEMINI_NUTRITION_PROMPT)
                )
                Toast.makeText(context, R.string.gemini_prompt_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.gemini_copy_prompt)) }
            importedNutrition?.let { response ->
                Text(stringResource(R.string.gemini_unverified), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (response.columns.all { it.nutrients.isEmpty() }) {
                            Text(stringResource(R.string.gemini_no_values))
                        }
                        response.columns.forEach { column ->
                            Text(
                                column.basis ?: stringResource(R.string.gemini_unknown_basis),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            column.nutrients.forEach { nutrient ->
                                Text("${nutrient.name}: ${nutrient.amount} ${nutrient.unit}")
                            }
                        }
                        response.uncertain.forEach { Text(it) }
                    }
                }
            }
            Text(stringResource(R.string.confident_numbers), style = MaterialTheme.typography.titleSmall)
            Text(
                model.numericFallback.numbers.joinToString(", ") {
                    "${it.number.amount} ${it.number.unit}".trim()
                }.ifEmpty { stringResource(R.string.no_numbers_detected) }
            )
            Text(stringResource(R.string.energy_hypotheses), style = MaterialTheme.typography.titleSmall)
            if (model.numericFallback.hypotheses.isEmpty()) {
                Text(stringResource(R.string.no_energy_hypotheses))
            } else {
                Text(stringResource(R.string.energy_hypotheses_caveat), style = MaterialTheme.typography.bodySmall)
                model.numericFallback.hypotheses.forEach { hypothesis ->
                    Text(stringResource(
                        if (hypothesis.carbsProteinAmbiguous) R.string.energy_hypothesis_ambiguous
                        else R.string.energy_hypothesis,
                        hypothesis.calories, hypothesis.fat, hypothesis.carbohydrates,
                        hypothesis.protein, hypothesis.calculatedCalories,
                    ))
                }
            }
            Text(stringResource(R.string.raw_text), style = MaterialTheme.typography.titleSmall)
            Text(model.recognizedText)
        }
    }
}