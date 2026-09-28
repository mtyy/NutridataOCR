package ee.mty.nutidataocr

import java.io.File
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replays recorded label OCR from the uncommitted label-samples folder (see docs/plan.md); skipped without it. */
class LabelSamplesTest {
    private data class Sample(val product: String, val photos: List<String>, val expected: Map<Nutrient, String>?)

    private enum class Outcome(val symbol: Char) { CORRECT('C'), MULTI('m'), WRONG('W'), MISSING('.') }

    private val directory = File(System.getenv("LABEL_SAMPLES") ?: "../label-samples")

    private val samples: List<Sample> by lazy { JSONArray(directory.resolve("expected.json").readText()).let { array ->
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val values = item.optJSONObject("per_100")
            Sample(
                product = item.getString("product"),
                photos = item.getJSONArray("photos").let { photos -> (0 until photos.length()).map { photos.getString(it) } },
                expected = values?.let { json ->
                    Nutrient.entries.mapNotNull { nutrient ->
                        json.optString(nutrient.name.lowercase(), "").takeIf { it.isNotEmpty() && it != "null" }?.let { nutrient to it }
                    }.toMap()
                },
            )
        }
    } }

    private fun fixture(variant: String, photo: String): List<OcrLine> {
        val json = JSONObject(directory.resolve("ocr/$variant/${photo.substringBeforeLast('.')}.json").readText())
        val lines = json.getJSONArray("lines")
        return (0 until lines.length()).map { index ->
            val line = lines.getJSONObject(index)
            val tokens = line.getJSONArray("tokens")
            OcrLine(
                text = line.getString("text"),
                confidence = line.getDouble("confidence").toFloat(),
                textHeightPx = line.getDouble("textHeightPx").toFloat(),
                tokens = (0 until tokens.length()).map { tokenIndex ->
                    val token = tokens.getJSONObject(tokenIndex)
                    val box = token.optJSONArray("box")?.let { OcrBox(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4)) }
                    OcrToken(token.getString("text"), token.getDouble("confidence").toFloat(), box)
                },
            )
        }
    }

    private fun normalize(amount: String, unit: String = "g"): String {
        val text = amount.trim().replace(',', '.')
        val comparison = text.takeWhile { it == '<' || it == '>' }
        val number = text.removePrefix(comparison).trim().toBigDecimal().let { if (unit == "mg") it.movePointLeft(3) else it }
        return comparison + number.stripTrailingZeros().toPlainString()
    }

    private fun outcome(expected: String?, actual: List<NutrientValue>?): Outcome {
        val values = actual.orEmpty().mapNotNull { value -> runCatching { normalize(value.amount, value.unit) }.getOrNull() }.distinct()
        val target = expected?.let { normalize(it) }
        return when {
            values.isEmpty() -> if (target == null) Outcome.CORRECT else Outcome.MISSING
            target == null -> Outcome.WRONG
            values == listOf(target) -> Outcome.CORRECT
            target in values -> Outcome.MULTI
            else -> Outcome.WRONG
        }
    }

    private class Result(val nutrients: Map<Nutrient, List<NutrientValue>>, val ready: Set<Nutrient>)

    private val modes: Map<String, () -> ((List<OcrLine>, Long) -> Result)> = mapOf(
        "line" to { NutritionScan().let { scan -> { lines, time -> Result(scan.observe(lines, time, isPhoto = true), scan.stableNutrients) } } },
        "spatial" to { LabelLayoutTracker().let { layout -> { lines, time -> layout.observe(lines, time, isPhoto = true); Result(layout.nutrients, layout.stableNutrients) } } },
        "auto" to { AutoNutrition().let { auto -> { lines, time -> Result(auto.observe(lines, time, isPhoto = true), auto.stableNutrients) } } },
    )

    private val scored = REQUIRED_SCAN_NUTRIENTS

    @Test
    fun report() {
        assumeTrue("No label samples in ${directory.absolutePath}", directory.resolve("expected.json").exists() && directory.resolve("ocr").isDirectory)
        val report = StringBuilder()
        val photoTotals = mutableMapOf<String, Map<String, Int>>()
        for (variant in listOf("photo", "frame1280", "frame")) {
            val totals = modes.keys.associateWith { mutableMapOf<String, Int>() }
            if (variant == "photo") photoTotals.putAll(totals)
            report.appendLine("===== $variant (all photos of a product in sequence; ! = marked ready) =====")
            for (sample in samples) {
                val row = StringBuilder(sample.product.take(38).padEnd(40))
                for ((name, factory) in modes) {
                    val count = totals.getValue(name)
                    sample.photos.forEach { photo ->
                        val single = factory()(fixture(variant, photo), 1_000L)
                        scored.forEach { count.merge("single_" + outcome(sample.expected?.get(it), single.nutrients[it]).name.lowercase(), 1, Int::plus) }
                    }
                    val observe = factory()
                    var result = Result(emptyMap(), emptySet())
                    sample.photos.forEachIndexed { index, photo -> result = observe(fixture(variant, photo), 1_000L * (index + 1)) }
                    val outcomes = scored.associateWith { outcome(sample.expected?.get(it), result.nutrients[it]) }
                    outcomes.forEach { (nutrient, outcome) ->
                        count.merge(outcome.name.lowercase(), 1, Int::plus)
                        if (nutrient in result.ready) count.merge("ready_" + outcome.name.lowercase(), 1, Int::plus)
                    }
                    row.append(" $name:").append(outcomes.entries.joinToString("") { (nutrient, outcome) ->
                        outcome.symbol + if (nutrient in result.ready) "!" else ""
                    })
                    val wrong = outcomes.filterValues { it == Outcome.WRONG || it == Outcome.MULTI }.keys
                    if (wrong.isNotEmpty()) row.append(" [").append(wrong.joinToString(" ") { "${it.name.lowercase()}=${result.nutrients[it]?.joinToString("/") { v -> v.amount }}" }).append("]")
                }
                report.appendLine(row)
            }
            totals.forEach { (name, counts) -> report.appendLine("TOTAL $variant $name: ${counts.toSortedMap()}") }
        }
        File("build/label-report.txt").writeText(report.toString())
        println(report)
        val dump = StringBuilder()
        for (sample in samples) {
            val auto = AutoNutrition()
            dump.appendLine("##### ${sample.product} ${sample.expected}")
            sample.photos.forEachIndexed { index, photo ->
                val lines = fixture("photo", photo)
                dump.appendLine("--- $photo rows")
                AutoNutrition.geometricRows(lines).forEach { dump.appendLine("  $it") }
                auto.observe(lines, 1_000L * (index + 1), isPhoto = true)
            }
            dump.appendLine(auto.describeEvidence())
            dump.appendLine("=> ${auto.nutrients} stable=${auto.stableNutrients} corrected=${auto.correctedNutrients}")
        }
        File("build/label-dump.txt").writeText(dump.toString())
        val auto = photoTotals.getValue("auto")
        directory.resolve("baseline.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }?.let { baseline ->
            baseline.optInt("min_correct").let { assertTrue(report.toString(), (auto["correct"] ?: 0) >= it) }
            baseline.optInt("min_single_correct").let { assertTrue(report.toString(), (auto["single_correct"] ?: 0) >= it) }
            if (baseline.has("max_ready_wrong")) {
                assertTrue(report.toString(), (auto["ready_wrong"] ?: 0) <= baseline.getInt("max_ready_wrong"))
            }
        }
        listOf("line", "spatial").forEach { mode ->
            assertTrue(mode, (auto["correct"] ?: 0) > (photoTotals.getValue(mode)["correct"] ?: 0))
        }
    }
}
