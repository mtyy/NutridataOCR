package ee.mty.nutidataocr

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.QRDecomposition

internal class LabelLayoutTracker {
    var words: List<OcrToken> = emptyList()
        private set
    var nutrients: Map<Nutrient, List<NutrientValue>> = emptyMap()
        private set
    var stableNutrients: Set<Nutrient> = emptySet()
        private set
    val fragmentCount: Int get() = fragments.size
    private val fragments = ArrayDeque<Fragment>()
    private val readingSources = mutableMapOf<Nutrient, Long>()
    private var resetAtMillis = Long.MIN_VALUE
    private var lastTimestamp = Long.MIN_VALUE

    fun observe(lines: List<OcrLine>, timestampMillis: Long, isPhoto: Boolean = false): List<OcrLine> {
        if (timestampMillis <= resetAtMillis || timestampMillis <= lastTimestamp ||
            (!isPhoto && lastTimestamp != Long.MIN_VALUE && timestampMillis - lastTimestamp < 300)
        ) return emptyList()
        lastTimestamp = timestampMillis
        val incoming = lines.flatMap { it.tokens }.filter {
            it.box?.valid == true && it.confidence.isFinite() && it.confidence >= 0.35f &&
                it.text.isNotBlank() && it.text.length <= 80
        }.take(MAX_WORDS)
        if (incoming.isEmpty()) return lines
        val match = fragments.mapNotNull { fragment ->
            align(incoming, fragment)?.let { fragment to it }
        }.maxByOrNull { it.second.support }
        val fragment: Fragment
        val transform: Transform
        if (match == null) {
            fragment = Fragment(timestampMillis)
            val angle = incoming.map { it.box!!.angle }.sorted().let { it[it.size / 2] }
            transform = Transform(cos(-angle), sin(-angle), 0.0, 0.0)
        } else {
            fragment = match.first
            transform = match.second.transform
            fragments.remove(fragment)
        }
        fragments.addLast(fragment)
        while (fragments.size > 3) fragments.removeFirst()
        fragment.update(incoming.map { it.copy(box = transform.apply(it.box!!)) })
        words = fragment.cells.map { it.token }
        val rows = rows(fragment.cells)
        val readings = rows.flatMap { row ->
            val text = row.line.text
            val parsed = if (row.overlaps) emptyMap() else parseNutrition(text)
            if (numberPattern.findAll(text).count() != parsed.values.sumOf { it.size }) emptyList()
            else parsed.map { (nutrient, values) ->
                Triple(nutrient, values, row.cells.all { it.stable })
            }
        }.groupBy { it.first }
        val currentNutrients = readings.mapValues { (_, entries) ->
            val distinct = entries.map { it.second }.distinct()
            if (distinct.size == 1) distinct.single() else distinct.flatten().distinct()
        }
        val currentStable = readings.filter { (nutrient, entries) ->
            currentNutrients[nutrient]?.size == 1 && entries.all { it.third } && entries.map { it.second }.distinct().size == 1
        }.keys
        nutrients = nutrients + currentNutrients
        stableNutrients = (stableNutrients - readingSources.filterValues { it == fragment.id }.keys - currentNutrients.keys) + currentStable
        currentNutrients.keys.forEach { readingSources[it] = fragment.id }
        return rows.map { it.line }
    }

    fun reset(timestampMillis: Long) {
        fragments.clear()
        readingSources.clear()
        words = emptyList()
        nutrients = emptyMap()
        stableNutrients = emptySet()
        resetAtMillis = timestampMillis
        lastTimestamp = Long.MIN_VALUE
    }

    private class Cell(token: OcrToken) {
        var box = token.box!!
        private val history = ArrayDeque<OcrToken>()
        private var selected = key(token.text)
        val token: OcrToken
            get() {
                val matching = history.filter { key(it.text) == selected }
                val agreement = scores().let { it.getValue(selected) / it.values.sum() }
                return OcrToken(matching.last().text, (matching.map { it.confidence }.average() * agreement).toFloat(), box)
            }
        val stable: Boolean
            get() = history.count { key(it.text) == selected && it.confidence >= 0.85f } >= 3 &&
                scores().let { it.getValue(selected) >= it.values.sum() * 0.75 }

        init { add(token) }

        fun add(token: OcrToken) {
            history.addLast(token)
            if (history.size > 8) history.removeFirst()
            val scores = scores()
            val best = scores.maxBy { it.value }
            if (selected !in scores || (history.count { key(it.text) == best.key } >= 2 &&
                    best.value > scores.getValue(selected) * 1.15)
            ) selected = best.key
            box = token.box!!
        }

        private fun scores(): Map<String, Double> {
            val scores = mutableMapOf<String, Double>()
            history.reversed().forEachIndexed { age, token ->
                val key = key(token.text)
                scores[key] = scores.getOrDefault(key, 0.0) + token.confidence.toDouble().pow(2) * 0.78.pow(age)
            }
            return scores
        }
    }

    private class Fragment(val id: Long) {
        val cells = mutableListOf<Cell>()

        fun update(incoming: List<OcrToken>) {
            val matches = incoming.map { token -> cells.filter { samePosition(it.box, token.box!!) } }
            val splitCells = matches.flatten().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val replaced = matches.filter { it.size > 1 }.flatten().toSet() + splitCells
            cells.removeAll(replaced)
            incoming.forEachIndexed { index, token ->
                val cell = matches[index].singleOrNull()?.takeUnless { it in replaced }
                if (cell == null) cells.add(Cell(token)) else cell.add(token)
            }
            while (cells.size > MAX_WORDS) cells.removeAt(0)
        }
    }

    private data class Row(val cells: List<Cell>) {
        val line: OcrLine get() = OcrLine(
            text = cells.joinToString(" ") { it.token.text },
            confidence = cells.minOf { it.token.confidence },
            textHeightPx = cells.minOf { it.box.height }.toFloat(),
            tokens = cells.map { it.token },
        )
        val overlaps: Boolean get() = cells.zipWithNext().any { (first, second) ->
            samePosition(first.box, second.box)
        }
    }

    private fun rows(cells: List<Cell>): List<Row> {
        val rows = mutableListOf<MutableList<Cell>>()
        for (cell in cells.sortedBy { it.box.centerY }) {
            val row = rows.minByOrNull { abs(it.first().box.centerY - cell.box.centerY) }
                ?.takeIf { abs(it.first().box.centerY - cell.box.centerY) <= minOf(it.first().box.height, cell.box.height) * 0.55 }
            if (row == null) rows.add(mutableListOf(cell)) else row.add(cell)
        }
        val joined = mutableListOf<Row>()
        for (row in rows.map { Row(it.sortedBy { cell -> cell.box.centerX }) }) {
            val previous = joined.lastOrNull()
            if (previous != null && previous.cells.none { cell -> cell.token.text.any { it.isDigit() } } &&
                abs(previous.cells.first().box.centerX - row.cells.first().box.centerX) < previous.cells.first().box.width &&
                row.cells.first().box.centerY - previous.cells.first().box.centerY < previous.cells.first().box.height * 1.8
            ) {
                val combined = Row(previous.cells + row.cells)
                val parsed = parseNutrition(combined.line.text)
                if (parsed.isNotEmpty() && parsed.keys != parseNutrition(row.line.text).keys) {
                    joined[joined.lastIndex] = combined
                    continue
                }
            }
            joined.add(row)
        }
        return joined
    }

    private data class Anchor(val current: OcrBox, val stored: OcrBox)
    private data class Alignment(val transform: Transform, val support: Int)
    private data class Transform(val real: Double, val imaginary: Double, val offsetX: Double, val offsetY: Double) {
        val scale: Double get() = hypot(real, imaginary)
        fun apply(box: OcrBox) = OcrBox(
            real * box.centerX - imaginary * box.centerY + offsetX,
            imaginary * box.centerX + real * box.centerY + offsetY,
            box.width * scale, box.height * scale, box.angle + atan2(imaginary, real),
        )
    }

    private fun align(incoming: List<OcrToken>, fragment: Fragment): Alignment? {
        fun anchors(tokens: List<OcrToken>) = tokens.filter { token ->
            token.confidence >= 0.85f && token.text.count { it.isLetter() } >= 3 && token.text.none { it.isDigit() }
        }.groupBy { key(it.text) }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val current = anchors(incoming)
        val stored = anchors(fragment.cells.map { it.token })
        val anchors = current.mapNotNull { (text, token) -> stored[text]?.let { Anchor(token.box!!, it.box!!) } }.take(12)
        if (anchors.size < 3) return null
        fun agrees(transform: Transform, anchor: Anchor): Boolean {
            val mapped = transform.apply(anchor.current)
            return hypot(mapped.centerX - anchor.stored.centerX, mapped.centerY - anchor.stored.centerY) <= anchor.stored.height * 0.65 &&
                mapped.height / anchor.stored.height in 0.65..1.5 &&
                abs(atan2(sin(mapped.angle - anchor.stored.angle), cos(mapped.angle - anchor.stored.angle))) < 0.2
        }
        var best: List<Anchor> = emptyList()
        for (first in anchors.indices) {
            for (second in first + 1 until anchors.size) {
                val pair = listOf(anchors[first], anchors[second])
                if (hypot(pair[0].stored.centerX - pair[1].stored.centerX, pair[0].stored.centerY - pair[1].stored.centerY) <
                    pair.maxOf { it.stored.height } * 4
                ) continue
                val candidate = fit(pair) ?: continue
                val inliers = anchors.filter { agrees(candidate, it) }
                if (inliers.size > best.size) best = inliers
            }
        }
        if (best.size < 3 || best.size < anchors.size * 0.65) return null
        val transform = fit(best) ?: return null
        return Alignment(transform, best.size).takeIf { best.all { agrees(transform, it) } }
    }

    private fun fit(anchors: List<Anchor>): Transform? {
        val matrix = anchors.flatMap { anchor -> listOf(
            doubleArrayOf(anchor.current.centerX, -anchor.current.centerY, 1.0, 0.0),
            doubleArrayOf(anchor.current.centerY, anchor.current.centerX, 0.0, 1.0),
        ) }.toTypedArray()
        val targets = anchors.flatMap { listOf(it.stored.centerX, it.stored.centerY) }.toDoubleArray()
        val solver = QRDecomposition(Array2DRowRealMatrix(matrix, false), 1e-8).solver
        if (!solver.isNonSingular) return null
        val values = solver.solve(ArrayRealVector(targets, false)).toArray()
        if (values.any { !it.isFinite() }) return null
        return Transform(values[0], values[1], values[2], values[3]).takeIf { it.scale in 0.2..5.0 }
    }

    private companion object {
        const val MAX_WORDS = 180
        val numberPattern = Regex("\\d+(?:[.,]\\d+)?")
        fun key(text: String) = text.lowercase(Locale.ROOT).replace(',', '.').trim()
        fun samePosition(first: OcrBox, second: OcrBox): Boolean {
            val overlap = minOf(first.centerX + first.width / 2, second.centerX + second.width / 2) -
                maxOf(first.centerX - first.width / 2, second.centerX - second.width / 2)
            return abs(first.centerY - second.centerY) < minOf(first.height, second.height) * 0.6 &&
                overlap > minOf(first.width, second.width) * 0.6
        }
    }
}