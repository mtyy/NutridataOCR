package ee.mty.nutidataocr

import java.text.Normalizer
import java.util.Locale
import org.apache.commons.text.similarity.LevenshteinDistance

internal enum class Nutrient {
    ENERGY_KJ, ENERGY_KCAL, FAT, SATURATES, CARBOHYDRATES, SUGARS, FIBRE, PROTEIN, SALT
}

internal data class NutrientValue(val amount: String, val unit: String)

internal val nutrientNames = mapOf(
    Nutrient.FAT to listOf(
        "total fat", "fat", "fats", "rasv", "rasvad", "rasvu", "tauki", "tauku", "taukus",
        "rasva", "rasvaa", "rasvat", "rasvoja", "riebalai", "riebalu", "riebalus",
        "fett", "fette", "tluszcz", "tluszcze", "tluszczu",
        "matieres grasses", "graisses", "lipides",
        "zsir", "grasimi", "masti", "mascobe", "tuky", "grassi",
        "vet", "vetten", "fedt",
    ),
    Nutrient.SATURATES to listOf(
        "saturated fat", "saturated fats", "saturated fatty acids", "saturated fatty acid", "saturates", "saturated",
        "kullastunud rasvhapped", "kullastunud rasvhappeid", "kullastunud",
        "piesatinatas taukskabes", "piesatinato taukskabju", "piesatinatie tauki",
        "tyydyttynytta rasvaa", "tyydyttynytta", "tyydyttyneet rasvahapot",
        "sociosios riebalu rugstys", "sociuju riebalu rugsciu", "sociuju riebalu rugstys",
        "gesattigte fettsauren", "gesaettigte fettsaeuren", "gesattigte fette",
        "kwasy tluszczowe nasycone", "kwasy nasycone",
        "acides gras satures", "telitett zsirsavak", "acizi grasi saturati", "zasicene masne kiseline",
        "nasicene mascobe", "nasycene mastne kyseliny", "nasytene mastne kyseliny", "acidi grassi saturi",
        "verzadigde vetzuren", "verzadigd vet", "verzadigde",
        "maettede fedtsyrer", "mettede fettsyrer", "mattat fett",
    ),
    Nutrient.CARBOHYDRATES to listOf(
        "carbohydrates", "carbohydrate", "carbs", "susivesikud", "susivesikuid", "susivesikute",
        "oglhidrati", "oglhidratu", "hiilihydraatit", "hiilihydraattia", "hiilihydraatteja",
        "angliavandeniai", "angliavandeniu", "weglowodany", "weglowodanow", "kohlenhydrate", "kohlenhydraten",
        "glucides", "glucide", "szenhidrat", "ugljikohidrati", "ogljikovi hidrati", "sacharidy", "carboidrati",
        "koolhydraten", "koolhydraat", "kulhydrat", "kulhydrater", "karbohydrat", "kolhydrat",
    ),
    Nutrient.SUGARS to listOf(
        "sugars", "sugar", "suhkrud", "suhkruid", "suhkur", "suhkru", "cukuri", "cukuru",
        "sokerit", "sokereita", "sokeria", "cukrus", "cukru", "zucker", "cukry", "cukier", "cukrow",
        "sucres", "cukrok", "zaharuri", "seceri", "sladkorji", "zuccheri",
        "suikers", "suiker", "sukkerarter", "sockerarter",
    ),
    Nutrient.FIBRE to listOf(
        "dietary fibre", "dietary fiber", "fibre", "fiber", "fibres", "fibers", "kiudained", "kiudaineid", "kiudaine",
        "skiedrvielas", "skiedrvielu", "ravintokuitu", "ravintokuitua", "kuitu", "kuitua", "skaidulines medziagos",
        "skaiduliniu medziagu", "ballaststoffe", "ballaststoffen", "blonnik", "blonnika",
        "fibres alimentaires", "rost", "vlakna", "prehranske vlaknine", "vlaknina",
        "voedingsvezel", "voedingsvezels", "vezels", "kostfibre", "kostfiber",
    ),
    Nutrient.PROTEIN to listOf(
        "proteins", "protein", "valk", "valgud", "valke", "valgu", "proteiin", "proteiinid",
        "olbaltumvielas", "olbaltumvielu", "proteiini", "proteiinia", "baltymai", "baltymu",
        "eiweiss", "eiweisse", "proteine", "proteines", "bialko", "bialka",
        "feherje", "bjelancevine", "beljakovine", "bilkoviny", "bielkoviny", "eiwitten", "eiwit",
    ),
    Nutrient.SALT to listOf(
        "salt", "salts", "sool", "soola", "sals", "sali", "suola", "suolaa", "druska", "druskos", "salz", "sol", "soli",
        "sel", "sare", "sale", "zout",
    ),
)
private val nutrientByName = nutrientNames.flatMap { (nutrient, names) ->
    names.map { name -> name to nutrient }
}.toMap()
private val namePattern = Regex(
    "(?<![\\p{L}])(?:" +
        nutrientByName.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
        ")(?![\\p{L}])"
)
private val valuePattern = Regex("""(?<![\p{L}\d+\-])(?<!\d[.,])([<>]?\s*\d+(?:[.,]\d+)?)\s*(kcal|kj|mg|g)\b""")
private val accentsPattern = Regex("\\p{M}+")
private val horizontalSpacePattern = Regex("[^\\S\\r\\n]+")
private val wordPattern = Regex("[\\p{L}\\d]+")
private val unsupportedFatPattern = Regex(
    "(?<![\\p{L}])(?:" + listOf(
        "(?:mono|poly)?unsaturated[ -]+fat(?:ty[ -]+acids?|s)?",
        "trans[ -]*(?:fats?|vet(?:ten)?|fett?e?|fedt)",
        // Otherwise these contain a fat alias, or sit one edit away from a saturates alias.
        "(?:enkelvoudig|meervoudig)?[ -]*onverzadigde?(?:[ -]+vet(?:ten)?)?",
        "(?:einfach|mehrfach)?[ -]*unges(?:a|ae)ttigte?(?:[ -]+fette?)?",
        "(?:enkelt|en|mono|fler|poly)?[ -]*um(?:ae|a|e)ttede?(?:[ -]+fe[dt]tsyrer)?",
        "(?:enkel|fler)[ -]*om(?:ae|a)ttat(?:[ -]+fett)?",
    ).joinToString("|") + ")(?![\\p{L}])"
)
private val fuzzyNamesByLength = nutrientByName.entries.filter { it.key.length >= 6 }.groupBy { it.key.length }
private val maxNameLength = nutrientByName.keys.maxOf { it.length }
private val maxNameWords = nutrientByName.keys.maxOf { name -> name.count { it == ' ' } + 1 } + 1
private val nameDistance = LevenshteinDistance(1)

private data class NutrientNameMatch(val nutrient: Nutrient?, val range: IntRange, val distance: Int = 0)

private fun findNutrientNames(line: String): List<NutrientNameMatch> {
    val excluded = unsupportedFatPattern.findAll(line).map { NutrientNameMatch(null, it.range) }.toList()
    fun overlaps(first: IntRange, second: IntRange) = first.first <= second.last && second.first <= first.last
    val exact = namePattern.findAll(line)
        .filter { match -> excluded.none { overlaps(it.range, match.range) } }
        .map { NutrientNameMatch(nutrientByName.getValue(it.value), it.range) }.toList()
    val candidates = exact.toMutableList()
    val words = wordPattern.findAll(line).toList()
    for (start in words.indices) {
        for (count in 1..minOf(maxNameWords, words.size - start)) {
            val selected = words.subList(start, start + count)
            if (selected.last().value.none { it.isLetter() }) break
            if (count > 1) {
                val gap = line.substring(selected[count - 2].range.last + 1, selected.last().range.first)
                if (gap.any { !it.isWhitespace() && it != '-' }) break
            }
            val text = selected.joinToString(" ") { it.value }
            if (text.length > maxNameLength + 1) break
            if (text.length < 6) continue
            val range = selected.first().range.first..selected.last().range.last
            if (excluded.any { overlaps(it.range, range) } ||
                exact.any { it.range.first <= range.first && it.range.last >= range.last }
            ) continue
            val matches = (text.length - 1..text.length + 1).flatMap { fuzzyNamesByLength[it].orEmpty() }
                .mapNotNull { entry ->
                    val distance = nameDistance.apply(text, entry.key)
                    if (distance < 0) null else NutrientNameMatch(entry.value, range, distance)
                }
            val bestDistance = matches.minOfOrNull { it.distance } ?: continue
            val best = matches.filter { it.distance == bestDistance }.distinctBy { it.nutrient }
            if (best.size == 1) candidates.add(best.single())
        }
    }
    val chosen = excluded.toMutableList()
    candidates.sortedWith(compareByDescending<NutrientNameMatch> { it.range.last - it.range.first }
        .thenBy { it.distance }).forEach { candidate ->
        if (chosen.none { overlaps(it.range, candidate.range) }) chosen.add(candidate)
    }
    return chosen.sortedBy { it.range.first }
}

internal fun normalizeLabelText(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(accentsPattern, "")
        .replace('\u0142', 'l')
        .replace("\u00df", "ss")
        .replace("\u00e6", "ae")
        .replace("\u0153", "oe")
        .replace('\u00f8', 'o')
        .replace(horizontalSpacePattern, " ")

/** Splits normalized text into each recognized nutrient name and the text up to the next name. */
internal fun nutrientSegments(normalizedLine: String): List<Pair<Nutrient, String>> {
    val names = findNutrientNames(normalizedLine)
    return names.mapIndexedNotNull { index, match ->
        val nutrient = match.nutrient ?: return@mapIndexedNotNull null
        val end = names.getOrNull(index + 1)?.range?.first ?: normalizedLine.length
        nutrient to normalizedLine.substring(match.range.last + 1, end)
    }
}

internal fun nutrientNameRanges(normalizedLine: String): List<Pair<Nutrient, IntRange>> =
    findNutrientNames(normalizedLine).mapNotNull { match -> match.nutrient?.let { it to match.range } }

internal fun parseNutrition(text: String): Map<Nutrient, List<NutrientValue>> {
    val normalized = normalizeLabelText(text)
    val result = mutableMapOf<Nutrient, List<NutrientValue>>()

    for (line in normalized.lineSequence()) {
        val values = readValues(line)
        values.filter { it.unit == "kJ" }.takeIf { it.isNotEmpty() }?.let {
            result.putIfAbsent(Nutrient.ENERGY_KJ, it)
        }
        values.filter { it.unit == "kcal" }.takeIf { it.isNotEmpty() }?.let {
            result.putIfAbsent(Nutrient.ENERGY_KCAL, it)
        }

        if (values.none { it.unit == "g" || it.unit == "mg" }) continue
        val names = findNutrientNames(line)
        names.forEachIndexed { index, match ->
            val nutrient = match.nutrient ?: return@forEachIndexed
            val end = names.getOrNull(index + 1)?.range?.first ?: line.length
            val nutrientValues = readValues(line.substring(match.range.last + 1, end))
                .filter { it.unit == "g" || it.unit == "mg" }
            if (nutrientValues.isNotEmpty()) {
                result.putIfAbsent(nutrient, nutrientValues)
            }
        }
    }
    return result.toSortedMap()
}

private fun readValues(text: String): List<NutrientValue> = valuePattern.findAll(text).map { match ->
    NutrientValue(
        amount = match.groupValues[1].replace(" ", "").replace(',', '.'),
        unit = match.groupValues[2].let { if (it == "kj") "kJ" else it },
    )
}.toList()