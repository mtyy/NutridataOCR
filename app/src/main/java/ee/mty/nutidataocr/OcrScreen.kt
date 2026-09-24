package ee.mty.nutidataocr

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
internal fun OcrScreen(model: OcrViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
                TextButton(onClick = { model.reset(SystemClock.elapsedRealtime()) }) {
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