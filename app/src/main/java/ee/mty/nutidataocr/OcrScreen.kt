package ee.mty.nutidataocr

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
internal fun OcrScreen(
    model: OcrViewModel,
    modifier: Modifier = Modifier,
    onFillDraft: ((LabelTransfer) -> Unit)? = null,
) {
    val context = LocalContext.current
    var resetAtMillis by rememberSaveable { mutableStateOf(Long.MIN_VALUE) }
    var pastedResponse by rememberSaveable { mutableStateOf("") }
    var importedResponse by rememberSaveable { mutableStateOf("") }
    var responseError by rememberSaveable { mutableStateOf<Int?>(null) }
    var geminiExpanded by rememberSaveable { mutableStateOf(false) }
    var chooseGeminiColumn by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var editingNutrient by rememberSaveable { mutableStateOf<Nutrient?>(null) }
    var reviewedLabel by remember { mutableStateOf<LabelTransfer?>(null) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    val importedNutrition = remember(importedResponse) {
        runCatching { parseGeminiResponse(importedResponse) }.getOrNull()
    }
    fun importResponse(text: String) {
        if (text.length > 65_536) {
            responseError = R.string.gemini_response_too_long
            return
        }
        pastedResponse = text
        val response = runCatching { parseGeminiResponse(text) }.getOrNull()
        if (response != null) {
            importedResponse = text
            responseError = null
            val applied = model.importGemini(response)
            chooseGeminiColumn = !applied && response.columns.isNotEmpty()
            if (applied && model.geminiNutrients.isNotEmpty()) {
                Toast.makeText(context, R.string.gemini_values_imported, Toast.LENGTH_SHORT).show()
            }
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
    val displayedNutrients = model.effectiveNutrients
    val readyCount = REQUIRED_SCAN_NUTRIENTS.count { it in model.readyNutrients }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }
    val scope = rememberCoroutineScope()
    var galleryJob by remember { mutableStateOf<Job?>(null) }
    fun newScan() {
        galleryJob?.cancel()
        galleryJob = null
        resetAtMillis = SystemClock.elapsedRealtime()
        model.reset(resetAtMillis)
        pastedResponse = ""
        importedResponse = ""
        responseError = null
        chooseGeminiColumn = false
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        newScan()
        val job = scope.launch {
            val scanned = scanGalleryPhotos(context, uris) { lines ->
                model.onTextRecognized(lines, SystemClock.elapsedRealtime(), isPhoto = true)
            }
            Toast.makeText(
                context,
                if (scanned == 0) context.getString(R.string.photo_no_text)
                else context.getString(R.string.gallery_scanned, scanned, uris.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
        galleryJob = job
        job.invokeOnCompletion { if (galleryJob === job) galleryJob = null }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    editingNutrient?.let { nutrient ->
        val suggestions = remember(nutrient) { model.unidentifiedAmounts }
        ManualNutrientDialog(
            nutrient = nutrient,
            initialAmount = model.manualEntryAmount(nutrient),
            suggestions = suggestions,
            onSave = { amount ->
                model.setManualNutrient(nutrient, amount).also { saved ->
                    if (saved) editingNutrient = null
                }
            },
            onClear = {
                model.useOcr(nutrient)
                editingNutrient = null
            },
            onDismiss = { editingNutrient = null },
        )
    }

    reviewedLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { reviewedLabel = null },
            title = { Text(stringResource(R.string.fill_review_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LABEL_COMPONENTS.forEach { (nutrient, component) ->
                        Text("${stringResource(nutrient.labelResource())}: ${label.amounts.getValue(component).stripTrailingZeros().toPlainString()} g")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reviewedLabel = null; onFillDraft?.invoke(label) }) {
                    Text(stringResource(R.string.fill_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewedLabel = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    reviewError?.let { error ->
        AlertDialog(
            onDismissRequest = { reviewError = null },
            title = { Text(stringResource(R.string.fill_review_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { reviewError = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (permissionGranted) {
            CameraPreview(
                // Gallery photos are one label; live frames must not mix into that scan.
                onTextRecognized = { lines, capturedAtMillis, isPhoto ->
                    if (galleryJob == null) model.onTextRecognized(lines, capturedAtMillis, isPhoto)
                },
                onGeminiPhoto = { photo, capturedAtMillis ->
                    if (capturedAtMillis <= resetAtMillis) {
                        photo.delete()
                    } else {
                        try {
                            openGemini(context, photo)
                        } catch (exception: Exception) {
                            photo.delete()
                            Log.e("GeminiShare", "Could not share photo", exception)
                            Toast.makeText(context, R.string.gemini_share_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(0.8f),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(0.8f),
                contentAlignment = Alignment.Center,
            ) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.allow_camera))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1.2f).padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (readyCount == REQUIRED_SCAN_NUTRIENTS.size) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        if (readyCount == REQUIRED_SCAN_NUTRIENTS.size) stringResource(R.string.scan_complete)
                        else stringResource(R.string.scan_progress, readyCount, REQUIRED_SCAN_NUTRIENTS.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (galleryJob != null) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_gallery),
                            contentDescription = stringResource(R.string.pick_photos),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                TextButton(onClick = { newScan() }) {
                    Text(stringResource(R.string.new_scan))
                }
            }
            LinearProgressIndicator(
                progress = { readyCount.toFloat() / REQUIRED_SCAN_NUTRIENTS.size },
                modifier = Modifier.fillMaxWidth(),
            )
            if (onFillDraft != null) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            reviewLabel(model.effectiveNutrients, model.manualNutrients.keys, model.geminiNutrients.keys)
                        }
                            .onSuccess { reviewedLabel = it }
                            .onFailure { reviewError = it.message }
                    },
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.fill_review))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                val warnings = nutrientWarnings(displayedNutrients)
                REQUIRED_SCAN_NUTRIENTS.forEach { nutrient ->
                    ScanNutrientRow(
                        nutrient = nutrient,
                        values = displayedNutrients[nutrient].orEmpty(),
                        stable = nutrient in model.stableNutrients && nutrient !in model.geminiNutrients,
                        corrected = nutrient in model.correctedNutrients,
                        manual = nutrient in model.manualNutrients,
                        gemini = nutrient in model.geminiNutrients,
                        warning = warnings[nutrient],
                        onEdit = { editingNutrient = nutrient },
                    )
                    HorizontalDivider()
                }
                ScanModeSelector(model.mode) { model.setMode(it, SystemClock.elapsedRealtime()) }
                ScannerSection(
                    title = stringResource(R.string.gemini_response),
                    expanded = geminiExpanded,
                    onToggle = { geminiExpanded = !geminiExpanded },
                ) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        importResponse(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                    }) { Text(stringResource(R.string.gemini_paste_import)) }
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
                    TextButton(onClick = { importResponse(pastedResponse) }, enabled = pastedResponse.isNotBlank()) {
                        Text(stringResource(R.string.gemini_import))
                    }
                    TextButton(onClick = {
                        copyGeminiPrompt(context)
                        Toast.makeText(context, R.string.gemini_prompt_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.gemini_copy_prompt)) }
                    importedNutrition?.let { response ->
                        Text(stringResource(R.string.gemini_unverified), style = MaterialTheme.typography.bodySmall)
                        if (chooseGeminiColumn) {
                            Text(stringResource(R.string.gemini_choose_column), style = MaterialTheme.typography.titleSmall)
                        }
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
                                    if (chooseGeminiColumn) {
                                        TextButton(onClick = {
                                            model.importGeminiColumn(column)
                                            chooseGeminiColumn = false
                                            Toast.makeText(context, R.string.gemini_values_imported, Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                                            Text(stringResource(R.string.gemini_use_column))
                                        }
                                    }
                                }
                                response.uncertain.forEach { Text(it) }
                            }
                        }
                    }
                }
                ScannerSection(
                    title = stringResource(R.string.scan_diagnostics),
                    expanded = diagnosticsExpanded,
                    onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
                ) {
                    if (model.mode != ScanMode.LINE) LabelLayoutPreview(model.layoutWords, model.layoutFragmentCount)
                    nutrients.forEach { (nutrient, values) ->
                        Text("${stringResource(nutrient.labelResource())}: ${values.joinToString(" / ") { "${it.amount} ${it.unit}" }}")
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
    }
}

@Composable
private fun ScanNutrientRow(
    nutrient: Nutrient,
    values: List<NutrientValue>,
    stable: Boolean,
    corrected: Boolean,
    manual: Boolean,
    gemini: Boolean,
    warning: NutrientWarning?,
    onEdit: () -> Unit,
) {
    val status = when {
        manual -> R.string.scan_manual
        values.size > 1 -> R.string.scan_multiple_columns
        gemini -> R.string.gemini
        stable -> R.string.scan_stable
        values.isEmpty() -> R.string.scan_not_found
        corrected -> R.string.scan_corrected
        else -> R.string.scan_collecting
    }
    val ready = stable || manual || (gemini && values.size == 1)
    val editLabel = stringResource(R.string.scan_edit_nutrient, stringResource(nutrient.labelResource()))
    val displayedValue = values.joinToString(" / ") { "${it.amount} ${it.unit}" }.ifEmpty { "-" }
    val warningText = warning?.let { stringResource(it.labelResource()) }
    val valueState = listOfNotNull(displayedValue, stringResource(status), warningText).joinToString(", ")
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
            .testTag("scan-${nutrient.name}").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when {
                warning != null -> Icons.Default.Warning
                ready -> Icons.Default.CheckCircle
                values.isEmpty() -> Icons.Default.Search
                values.size > 1 -> Icons.Default.Warning
                else -> Icons.Default.Info
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = when {
                warning != null -> MaterialTheme.colorScheme.error
                ready -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(nutrient.labelResource()), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            warningText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(
            onClick = onEdit,
            modifier = Modifier.weight(0.8f).heightIn(min = 48.dp).semantics {
                contentDescription = editLabel
                stateDescription = valueState
            },
        ) {
            Text(
                displayedValue,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ManualNutrientDialog(
    nutrient: Nutrient,
    initialAmount: String,
    suggestions: List<String>,
    onSave: (String) -> Boolean,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by rememberSaveable(nutrient) { mutableStateOf(initialAmount) }
    var invalid by rememberSaveable(nutrient) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    fun save() {
        if (!invalid) invalid = !onSave(amount)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(nutrient.labelResource())) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        if (it.length <= 64) {
                            amount = it
                            invalid = false
                        } else invalid = true
                    },
                    label = { Text(stringResource(R.string.scan_amount_grams)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.scan_invalid_amount)) }) else null,
                    trailingIcon = {
                        Row {
                            listOf(
                                Triple(-1, Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.scan_decimal_left),
                                Triple(1, Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.scan_decimal_right),
                            ).forEach { (places, icon, label) ->
                                val shifted = shiftDecimalPoint(amount, places)
                                IconButton(
                                    onClick = { shifted?.let { amount = it; invalid = false } },
                                    enabled = shifted != null,
                                ) { Icon(icon, contentDescription = stringResource(label)) }
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                if (suggestions.isNotEmpty()) {
                    Text(stringResource(R.string.scan_unidentified_numbers), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    amount = suggestion
                                    invalid = false
                                },
                                label = { Text("$suggestion g") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !invalid) { Text(stringResource(R.string.scan_save_value)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(stringResource(R.string.scan_clear_value)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
    LaunchedEffect(Unit) {
        if (suggestions.isEmpty()) focusRequester.requestFocus()
    }
}

@Composable
private fun ScannerSection(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val state = stringResource(if (expanded) R.string.scan_expanded else R.string.scan_collapsed)
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().semantics { stateDescription = state }) {
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
    }
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** Feeds each photo through OCR as one more frame of the same label; returns how many yielded text. */
private suspend fun scanGalleryPhotos(context: Context, uris: List<Uri>, onFrame: (List<OcrLine>) -> Unit): Int {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        var scanned = 0
        for (uri in uris) {
            try {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
                val lines = recognizer.process(image).await().toOcrLines()
                if (lines.isNotEmpty()) {
                    onFrame(lines)
                    scanned++
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("CameraOcr", "Could not scan gallery photo", exception)
            }
        }
        return scanned
    } finally {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
    }
}

private fun NutrientWarning.labelResource() = when (this) {
    NutrientWarning.OVER_100 -> R.string.scan_warning_over_100
    NutrientWarning.HIGH_SALT -> R.string.scan_warning_high_salt
    NutrientWarning.OVER_FAT -> R.string.scan_warning_over_fat
    NutrientWarning.OVER_CARBOHYDRATES -> R.string.scan_warning_over_carbohydrates
    NutrientWarning.MACROS_OVER_100 -> R.string.scan_warning_macros_over_100
}

private fun Nutrient.labelResource() = when (this) {
    Nutrient.ENERGY_KJ, Nutrient.ENERGY_KCAL -> R.string.energy
    Nutrient.FAT -> R.string.fat
    Nutrient.SATURATES -> R.string.saturates
    Nutrient.CARBOHYDRATES -> R.string.carbohydrates
    Nutrient.SUGARS -> R.string.sugars
    Nutrient.FIBRE -> R.string.fibre
    Nutrient.PROTEIN -> R.string.protein
    Nutrient.SALT -> R.string.salt
}