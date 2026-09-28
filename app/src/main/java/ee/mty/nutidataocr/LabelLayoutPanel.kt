package ee.mty.nutidataocr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ScanModeSelector(mode: ScanMode, onChange: (ScanMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(R.string.scan_mode), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScanMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == mode,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, ScanMode.entries.size),
                ) {
                    Text(stringResource(when (option) {
                        ScanMode.AUTO -> R.string.scan_mode_auto
                        ScanMode.LINE -> R.string.scan_mode_line
                        ScanMode.SPATIAL -> R.string.scan_mode_spatial
                    }))
                }
            }
        }
    }
}

@Composable
internal fun LabelLayoutPreview(words: List<OcrToken>, fragments: Int) {
    Text(stringResource(R.string.layout_matrix), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.layout_counts, words.size, fragments), style = MaterialTheme.typography.bodySmall)
    if (words.isEmpty()) {
        Text(stringResource(R.string.layout_empty))
        return
    }
    val left = words.minOf { it.box!!.centerX - it.box.width / 2 }
    val top = words.minOf { it.box!!.centerY }
    val characterWidth = words.map { it.box!!.width / it.text.length.coerceAtLeast(1) }.sorted().let { it[it.size / 2] }
    val rowHeight = words.map { it.box!!.height }.sorted().let { it[it.size / 2] }
    val rows = words.groupBy { ((it.box!!.centerY - top) / rowHeight).roundToInt() }.toSortedMap()
    val matrix = buildAnnotatedString {
        rows.values.forEachIndexed { index, row ->
            if (index > 0) append('\n')
            var column = 0
            row.sortedBy { it.box!!.centerX }.forEach { token ->
                val position = ((token.box!!.centerX - token.box.width / 2 - left) / characterWidth).roundToInt().coerceIn(0, 300)
                val spaces = (position - column).coerceAtLeast(if (column == 0) 0 else 1)
                append(" ".repeat(spaces))
                withStyle(SpanStyle(color = if (token.confidence >= 0.85f) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)) {
                    append(token.text)
                }
                column += spaces + token.text.length
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
    ) {
        Text(matrix, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
    }
}