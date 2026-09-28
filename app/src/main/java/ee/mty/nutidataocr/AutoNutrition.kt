package ee.mty.nutidataocr

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Combines line, geometric-row, sentence and spatial-tracker readings as weighted candidates, then picks the
 * assignment that best agrees with label arithmetic (energy from macros, kJ/kcal, sub-nutrients within totals).
 */
internal class AutoNutrition {
    private class Evidence(var weight: Double, var literal: Double, var hits: Int)
    internal data class Choice(val amount: BigDecimal, val comparison: String)

    private val layout = LabelLayoutTracker()
    private val evidence = mutableMapOf<Nutrient, MutableMap<Choice, Evidence>>()
    private var resetAtMillis = Long.MIN_VALUE
    private var lastTimestamp = Long.MIN_VALUE

    var nutrients: Map<Nutrient, List<NutrientValue>> = emptyMap()
        private set
    var stableNutrients: Set<Nutrient> = emptySet()
        private set
    /** Values that only exist after repairing likely OCR damage, such as a lost decimal separator. */
    var correctedNutrients: Set<Nutrient> = emptySet()
        private set
    val words: List<OcrToken> get() = layout.words
    val fragmentCount: Int get() = layout.fragmentCount

    fun observe(lines: List<OcrLine>, timestampMillis: Long, isPhoto: Boolean = false): Map<Nutrient, List<NutrientValue>> {
        if (timestampMillis <= resetAtMillis || timestampMillis <= lastTimestamp ||
            (!isPhoto && lastTimestamp != Long.MIN_VALUE && timestampMillis - lastTimestamp < 300)
        ) return nutrients
        lastTimestamp = timestampMillis
        val frame = mutableMapOf<Nutrient, MutableMap<Choice, Pair<Double, Double>>>()
        fun add(nutrient: Nutrient, choice: Choice, weight: Double, literal: Boolean) {
            val values = frame.getOrPut(nutrient) { mutableMapOf() }
            val (total, literalWeight) = values[choice] ?: (0.0 to 0.0)
            values[choice] = maxOf(total, weight) to maxOf(literalWeight, if (literal) weight else 0.0)
        }
        fun read(text: String, weight: Double, columns: Int, sentence: Boolean = false) {
            val normalized = normalizeLabelText(text)
            readEnergy(normalized).forEach { (nutrient, choice, literal) -> add(nutrient, choice, weight, literal) }
            for ((nutrient, segment) in nutrientSegments(normalized)) {
                val amounts = readAmounts(segment)
                    .filter { !sentence || (it.start <= SENTENCE_REACH && it.hasUnit) }
                amounts.take(columns).forEachIndexed { column, amount ->
                    amount.readings.forEach { (choice, prior, literal) -> add(nutrient, choice, weight * prior * (1.0 - 0.1 * column), literal) }
                }
            }
        }
        lines.forEach { read(it.text, it.confidence.weight(), 1) }
        geometricRows(lines).forEach { read(it, 0.3, 3) }
        pairedValues(lines).forEach { (nutrient, texts) ->
            texts.forEachIndexed { column, (text, weight) ->
                readAmounts(text).firstOrNull()?.readings?.forEach { (choice, prior, literal) ->
                    add(nutrient, choice, weight * prior * (1.0 - 0.1 * column), literal)
                }
            }
        }
        layout.observe(lines, timestampMillis, isPhoto).forEach { read(it.text, 0.8, 3) }
        read(lines.joinToString(" ") { it.text }, 0.5, 1, sentence = true)

        evidence.values.forEach { values -> values.values.forEach { it.weight *= DECAY; it.literal *= DECAY } }
        for ((nutrient, values) in frame) {
            val stored = evidence.getOrPut(nutrient) { mutableMapOf() }
            for ((choice, weights) in values) {
                val entry = stored.getOrPut(choice) { Evidence(0.0, 0.0, 0) }
                entry.weight += weights.first
                entry.literal += weights.second
                if (weights.second > 0.0) entry.hits++
            }
            stored.entries.sortedByDescending { it.value.weight }.drop(MAX_CANDIDATES * 2).forEach { stored.remove(it.key) }
        }
        solve()
        return nutrients
    }

    fun reset(timestampMillis: Long) {
        layout.reset(timestampMillis)
        evidence.clear()
        nutrients = emptyMap()
        stableNutrients = emptySet()
        correctedNutrients = emptySet()
        resetAtMillis = timestampMillis
        lastTimestamp = Long.MIN_VALUE
    }

    private fun solve() {
        fun options(nutrient: Nutrient): List<Choice?> {
            // Repairs are weak on their own, so they only need to survive until arithmetic can confirm them.
            val values = evidence[nutrient].orEmpty().entries
                .filter { it.value.weight >= if (it.value.literal > 0.0) MIN_WEIGHT else MIN_REPAIR_WEIGHT }
                .sortedByDescending { it.value.weight }.take(MAX_CANDIDATES)
            return values.map { it.key } + null
        }
        fun score(nutrient: Nutrient, choice: Choice?): Double {
            val values = evidence[nutrient].orEmpty()
            if (choice == null) return if (values.values.any { it.literal >= MIN_WEIGHT }) -0.5 else 0.0
            return values.getValue(choice).weight / values.values.maxOf { it.weight }
        }
        val kcalOptions = options(Nutrient.ENERGY_KCAL)
        val kjOptions = options(Nutrient.ENERGY_KJ)
        val fatOptions = options(Nutrient.FAT)
        val carbOptions = options(Nutrient.CARBOHYDRATES)
        val proteinOptions = options(Nutrient.PROTEIN)
        val fibreOptions = options(Nutrient.FIBRE)
        val saturateOptions = options(Nutrient.SATURATES)
        val sugarOptions = options(Nutrient.SUGARS)
        val saltOptions = options(Nutrient.SALT)
        val maxKcal = (kcalOptions.mapNotNull { it?.amount?.toDouble() } +
            kjOptions.mapNotNull { it?.amount?.toDouble()?.div(KJ_PER_KCAL) }).maxOrNull() ?: 1.0
        var best: Map<Nutrient, Choice?> = emptyMap()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestEnergyOk = false
        for (kcal in kcalOptions) for (kj in kjOptions) {
            var energyScore = score(Nutrient.ENERGY_KCAL, kcal) + score(Nutrient.ENERGY_KJ, kj)
            if (kcal != null && kj != null) {
                energyScore += if (kj.amount.toDouble() / kcal.amount.toDouble() in 4.05..4.3) 1.0 else -2.0
            }
            val calories = kcal?.amount?.toDouble() ?: kj?.amount?.toDouble()?.div(KJ_PER_KCAL)
            if (calories != null) energyScore += 0.3 * calories / maxKcal
            for (fat in fatOptions) for (carbs in carbOptions) for (protein in proteinOptions) for (fibre in fibreOptions) {
                val main = listOf(fat, carbs, protein, fibre)
                var total = energyScore + score(Nutrient.FAT, fat) + score(Nutrient.CARBOHYDRATES, carbs) +
                    score(Nutrient.PROTEIN, protein) + score(Nutrient.FIBRE, fibre) -
                    COLLISION_PENALTY * main.indices.sumOf { i -> (i + 1 until main.size).count { same(main[i], main[it]) } } -
                    atypical(Nutrient.PROTEIN, protein) - atypical(Nutrient.FIBRE, fibre)
                var energyOk = false
                if (calories != null) {
                    val calculated = 9 * (fat?.value() ?: 0.0) + 4 * (carbs?.value() ?: 0.0) +
                        4 * (protein?.value() ?: 0.0) + 2 * (fibre?.value() ?: 0.0)
                    val tolerance = maxOf(12.0, calories * 0.12)
                    if (fat != null && carbs != null && protein != null) {
                        energyOk = abs(calculated - calories) <= tolerance
                        total += if (energyOk) 2.5 else -2.5
                    } else if (calculated > calories + tolerance) total -= 2.5
                }
                fun collisions(choice: Choice?, others: List<Choice?>) = COLLISION_PENALTY * others.count { same(choice, it) }
                val saturates = saturateOptions.maxBy { choice ->
                    score(Nutrient.SATURATES, choice) - collisions(choice, main) -
                        if (choice != null && fat != null && choice.value() > fat.value() * 1.02) 3.0 else 0.0
                }
                val sugars = sugarOptions.maxBy { choice ->
                    score(Nutrient.SUGARS, choice) - collisions(choice, listOf(fat, protein, fibre, saturates)) -
                        if (choice != null && carbs != null && choice.value() > carbs.value() * 1.02) 3.0 else 0.0
                }
                val grams = listOfNotNull(fat, carbs, protein, fibre).sumOf { it.value() }
                val salt = saltOptions.maxBy { choice ->
                    score(Nutrient.SALT, choice) - collisions(choice, main + saturates + sugars) - atypical(Nutrient.SALT, choice) -
                        if (choice != null && grams + choice.value() > 101.0) 3.0 else 0.0
                }
                total += score(Nutrient.SATURATES, saturates) + score(Nutrient.SUGARS, sugars) + score(Nutrient.SALT, salt) -
                    collisions(saturates, main) - collisions(sugars, listOf(fat, protein, fibre, saturates)) -
                    collisions(salt, main + saturates + sugars) - atypical(Nutrient.SALT, salt)
                if (saturates != null && fat != null && saturates.value() > fat.value() * 1.02) total -= 3.0
                if (sugars != null && carbs != null && sugars.value() > carbs.value() * 1.02) total -= 3.0
                if (grams + (salt?.value() ?: 0.0) > 101.0) total -= 3.0
                if (total > bestScore) {
                    bestScore = total
                    // A portion column can be self-consistent too; only the largest energy basis confirms per-100 values.
                    bestEnergyOk = energyOk && calories != null && calories >= maxKcal * 0.9 &&
                        listOf(Nutrient.FAT to fat, Nutrient.CARBOHYDRATES to carbs, Nutrient.PROTEIN to protein)
                            .all { (nutrient, choice) -> score(nutrient, choice) >= MIN_READY_SCORE }
                    best = mapOf(
                        Nutrient.ENERGY_KCAL to kcal, Nutrient.ENERGY_KJ to kj, Nutrient.FAT to fat,
                        Nutrient.SATURATES to saturates, Nutrient.CARBOHYDRATES to carbs, Nutrient.SUGARS to sugars,
                        Nutrient.FIBRE to fibre, Nutrient.PROTEIN to protein, Nutrient.SALT to salt,
                    )
                }
            }
        }
        val chosen = best.filterValues { it != null }.mapValues { it.value!! }
        nutrients = chosen.mapValues { (nutrient, choice) ->
            val unit = when (nutrient) {
                Nutrient.ENERGY_KJ -> "kJ"
                Nutrient.ENERGY_KCAL -> "kcal"
                else -> "g"
            }
            listOf(NutrientValue(choice.comparison + choice.amount.stripTrailingZeros().toPlainString(), unit))
        }.toSortedMap()
        correctedNutrients = chosen.filter { (nutrient, choice) ->
            evidence.getValue(nutrient).getValue(choice).let { it.literal < it.weight * 0.5 }
        }.keys
        stableNutrients = chosen.filter { (nutrient, choice) ->
            val entry = evidence.getValue(nutrient).getValue(choice)
            val weaker = chosen.any { (other, value) ->
                other != nutrient && other !in ENERGY && setOf(nutrient, other) !in RELATED &&
                    same(choice, value) && evidence.getValue(other).getValue(value).weight >= entry.weight
            }
            nutrient !in correctedNutrients && !weaker && atypical(nutrient, choice) == 0.0 &&
                entry.weight >= evidence.getValue(nutrient).values.maxOf { it.weight } * MIN_READY_SCORE &&
                !(nutrient == Nutrient.SALT && choice.value() > 6.0) &&
                (entry.hits >= MIN_STABLE_HITS || (bestEnergyOk && entry.hits >= 1))
        }.keys
    }

    private fun same(first: Choice?, second: Choice?) = first != null && second != null &&
        first.comparison == second.comparison && first.amount.signum() > 0 && first.amount.compareTo(second.amount) == 0

    private fun atypical(nutrient: Nutrient, choice: Choice?): Double {
        val value = choice?.value() ?: return 0.0
        return when (nutrient) {
            Nutrient.SALT -> if (value > 60) 3.0 else if (value > 10) 1.0 else 0.0
            Nutrient.PROTEIN -> if (value > 50) 1.0 else 0.0
            Nutrient.FIBRE -> if (value > 40) 1.0 else 0.0
            else -> 0.0
        }
    }

    internal fun describeEvidence(): String = evidence.toSortedMap().entries.joinToString("\n") { (nutrient, values) ->
        "$nutrient: " + values.entries.sortedByDescending { it.value.weight }.joinToString("  ") { (choice, entry) ->
            "${choice.comparison}${choice.amount.toPlainString()}=%.2f/%.2f/%d".format(entry.weight, entry.literal, entry.hits)
        }
    }

    private fun Choice.value() = amount.toDouble()

    private fun Float.weight(): Double = if (isFinite()) 0.4 + 0.6 * coerceIn(0f, 1f) else 0.7

    internal companion object {
        private const val DECAY = 0.85
        private const val MAX_CANDIDATES = 4
        private const val MIN_STABLE_HITS = 3
        private const val MIN_WEIGHT = 0.25
        private const val MIN_REPAIR_WEIGHT = 0.05
        private const val COLLISION_PENALTY = 0.5
        private const val MIN_READY_SCORE = 0.5
        private val ENERGY = setOf(Nutrient.ENERGY_KJ, Nutrient.ENERGY_KCAL)
        private val RELATED = setOf(setOf(Nutrient.CARBOHYDRATES, Nutrient.SUGARS))
        private val REFERENCE_INTAKE = mapOf(Nutrient.ENERGY_KJ to BigDecimal(8400), Nutrient.ENERGY_KCAL to BigDecimal(2000))
        private const val MAX_ACROSS = 0.7
        private val UNIT_TOKENS = setOf("g", "9", "q", "mg")
        private const val SENTENCE_REACH = 24
        private const val KJ_PER_KCAL = 4.184

        internal data class Amount(val start: Int, val hasUnit: Boolean, val readings: List<Triple<Choice, Double, Boolean>>)

        val amountPattern = Regex("""(?<![\p{L}\d])(?<!\d[.,])([<>]?)\s*(\d{1,4}(?:[.,]\d{1,3})?)(?:\s*(mg|g|q)(?![\p{L}])|\s(9)(?![\p{L}\d.,]))?""")
        val kjPattern = Regex("""(?<![\d.,])(\d{2,4}(?:[.,]\d)?)\s*k\s*k?j""")
        val kcalPattern = Regex("""(?<![\d.,])(\d{1,4}(?:[.,]\d)?)\s*kca""")
        val pairPattern = Regex("""(?<![\d.,])(\d{2,4})\s*[/|]\s*(\d{1,3})(?![\d.,])""")

        fun choice(text: String, comparison: String = ""): Choice? =
            text.replace(',', '.').toBigDecimalOrNull()?.takeIf { it <= BigDecimal(100) }?.let { Choice(it, comparison) }

        /** Readings for each value in [segment], nearest first; each value yields its plausible OCR interpretations. */
        fun readAmounts(segment: String): List<Amount> =
            amountPattern.findAll(segment).mapNotNull { match ->
                val after = segment.substring(match.range.last + 1).trimStart()
                if (after.startsWith("%") || Regex("^(kj|kcal|kg|ml|l\\b)").containsMatchIn(after)) return@mapNotNull null
                val comparison = match.groupValues[1]
                val digits = match.groupValues[2]
                val unit = match.groupValues[3].ifEmpty { match.groupValues[4] }
                val readings = mutableListOf<Triple<Choice, Double, Boolean>>()
                fun add(text: String, prior: Double, literal: Boolean) {
                    choice(text, comparison)?.let { readings += Triple(it, prior, literal) }
                }
                fun addWithLostSeparator(text: String, prior: Double) {
                    if (text.all { it.isDigit() } && text.length in 2..3 && !text.startsWith("0")) {
                        add(text.dropLast(1) + "." + text.last(), prior, false)
                    }
                }
                when {
                    unit == "mg" -> digits.replace(',', '.').toBigDecimalOrNull()?.movePointLeft(3)
                        ?.let { readings += Triple(Choice(it, comparison), 1.0, true) }
                    unit.isNotEmpty() -> {
                        if (digits.length >= 2 && digits.startsWith("0") && digits.all { it.isDigit() }) {
                            add("0." + digits.drop(1), 0.9, true)
                        } else add(digits, 1.0, true)
                        addWithLostSeparator(digits, 0.15)
                    }
                    else -> {
                        add(digits, 0.35, true)
                        if (digits.length >= 2 && digits.last() == '9') {
                            val trimmed = digits.dropLast(1).trimEnd('.', ',')
                            if (trimmed.startsWith("0") && trimmed.length >= 2 && trimmed.all { it.isDigit() }) {
                                add("0." + trimmed.drop(1), 0.7, true)
                            } else add(trimmed, 0.7, true)
                            addWithLostSeparator(trimmed, 0.3)
                        }
                        addWithLostSeparator(digits, 0.1)
                    }
                }
                readings.takeIf { it.isNotEmpty() }?.let { Amount(match.range.first, unit.isNotEmpty(), it) }
            }.toList()

        fun readEnergy(text: String): List<Triple<Nutrient, Choice, Boolean>> {
            val result = mutableListOf<Triple<Nutrient, Choice, Boolean>>()
            fun energy(nutrient: Nutrient, digits: String) {
                digits.replace(',', '.').toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < BigDecimal(4000) }
                    ?.takeUnless { it.compareTo(REFERENCE_INTAKE.getValue(nutrient)) == 0 }
                    ?.let { result += Triple(nutrient, Choice(it, ""), true) }
            }
            kjPattern.findAll(text).forEach { energy(Nutrient.ENERGY_KJ, it.groupValues[1]) }
            kcalPattern.findAll(text).forEach { energy(Nutrient.ENERGY_KCAL, it.groupValues[1]) }
            if ("energ" in text || "kj" in text || "kcal" in text) {
                pairPattern.findAll(text).forEach { match ->
                    val kj = match.groupValues[1].toDouble()
                    val kcal = match.groupValues[2].toDouble()
                    if (kcal > 0 && kj / kcal in 4.05..4.3) {
                        energy(Nutrient.ENERGY_KJ, match.groupValues[1])
                        energy(Nutrient.ENERGY_KCAL, match.groupValues[2])
                    }
                }
            }
            return result
        }

        /**
         * Pairs each number with the nutrient name whose text baseline it continues, using the local angle of both
         * words so rotated, skewed or perspective-distorted tables still pair row by row. Returns values per name in
         * reading order with a pairing weight.
         */
        fun pairedValues(lines: List<OcrLine>): List<Pair<Nutrient, List<Pair<String, Double>>>> {
            data class Anchor(val nutrient: Nutrient, val box: OcrBox)
            data class Item(val text: String, val box: OcrBox)
            val anchors = mutableListOf<Anchor>()
            val items = mutableListOf<Item>()
            for (line in lines) {
                val tokens = line.tokens.filter { it.box?.valid == true }
                if (tokens.isEmpty()) continue
                val texts = tokens.map { normalizeLabelText(it.text) }
                val starts = texts.runningFold(0) { start, text -> start + text.length + 1 }
                for ((nutrient, range) in nutrientNameRanges(texts.joinToString(" "))) {
                    anchors += Anchor(nutrient, tokens[starts.indexOfLast { it <= range.last }.coerceIn(tokens.indices)].box!!)
                }
                var index = 0
                while (index < tokens.size) {
                    val text = texts[index]
                    val next = texts.getOrNull(index + 1)
                    if (text.any { it.isDigit() } && '%' !in text && "kj" !in text && "kcal" !in text && next != "%") {
                        if (next in UNIT_TOKENS && text.last().isDigit()) {
                            items += Item("$text $next", tokens[index].box!!)
                            index += 2
                            continue
                        }
                        items += Item(text, tokens[index].box!!)
                    }
                    index++
                }
            }
            val paired = mutableMapOf<Anchor, MutableList<Triple<Double, String, Double>>>()
            for (item in items) {
                var best: Anchor? = null
                var bestCost = Double.MAX_VALUE
                var bestAlong = 0.0
                var bestAcross = 0.0
                for (anchor in anchors) {
                    val theta = atan2(sin(anchor.box.angle) + sin(item.box.angle), cos(anchor.box.angle) + cos(item.box.angle))
                    val dx = item.box.centerX - anchor.box.centerX
                    val dy = item.box.centerY - anchor.box.centerY
                    val height = (anchor.box.height + item.box.height) / 2
                    val along = (dx * cos(theta) + dy * sin(theta)) / height
                    val across = abs(-dx * sin(theta) + dy * cos(theta)) / height
                    val cost = across + 0.01 * abs(along)
                    if (across < MAX_ACROSS && cost < bestCost) {
                        best = anchor
                        bestCost = cost
                        bestAlong = along
                        bestAcross = across
                    }
                }
                val anchor = best ?: continue
                val weight = (if (bestAlong > 0) 0.9 else 0.5) * (1.0 - 0.5 * bestAcross / MAX_ACROSS)
                paired.getOrPut(anchor) { mutableListOf() } += Triple(bestAlong, item.text, weight)
            }
            return paired.map { (anchor, values) ->
                anchor.nutrient to values.sortedWith(compareBy({ it.first < 0 }, { abs(it.first) })).map { it.second to it.third }
            }
        }

        /** Rebuilds text rows from word boxes after undoing the dominant text rotation. */
        fun geometricRows(lines: List<OcrLine>): List<String> {
            val tokens = lines.flatMap { it.tokens }.filter { it.box?.valid == true && it.text.isNotBlank() }
            if (tokens.size < 2) return emptyList()
            val angle = atan2(tokens.sumOf { sin(it.box!!.angle) }, tokens.sumOf { cos(it.box!!.angle) })
            val cosine = cos(-angle)
            val sine = sin(-angle)
            data class Word(val text: String, val x: Double, val y: Double, val height: Double)
            val words = tokens.map { token ->
                val box = token.box!!
                Word(token.text, box.centerX * cosine - box.centerY * sine, box.centerX * sine + box.centerY * cosine, box.height)
            }
            val rows = mutableListOf<MutableList<Word>>()
            for (word in words.sortedBy { it.y }) {
                val row = rows.lastOrNull { row ->
                    abs(row.sumOf { it.y } / row.size - word.y) < minOf(word.height, row.minOf { it.height }) * 0.5
                }
                if (row == null) rows.add(mutableListOf(word)) else row.add(word)
            }
            return rows.map { row -> row.sortedBy { it.x }.joinToString(" ") { it.text } }
        }
    }
}
