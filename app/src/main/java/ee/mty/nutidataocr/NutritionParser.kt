package ee.mty.nutidataocr

import java.text.Normalizer
import java.util.Locale

internal enum class Nutrient {
    ENERGY_KJ, ENERGY_KCAL, FAT, SATURATES, CARBOHYDRATES, SUGARS, FIBRE, PROTEIN, SALT
}

internal data class NutrientValue(val amount: String, val unit: String)

private val nutrientNames = mapOf(
    Nutrient.FAT to listOf(
        "total fat", "fat", "rasv", "rasvad", "tauki", "rasva", "rasvaa", "rasvat",
        "riebalai", "fett", "tluszcz",
    ),
    Nutrient.SATURATES to listOf(
        "saturated fat", "saturated fatty acids", "saturates",
        "kullastunud rasvhapped", "kullastunud rasvhappeid", "kullastunud",
        "piesatinatas taukskabes", "piesatinatie tauki",
        "tyydyttynytta rasvaa", "tyydyttynytta", "tyydyttyneet rasvahapot",
        "sociosios riebalu rugstys", "sociuju riebalu rugsciu",
        "gesattigte fettsauren", "kwasy tluszczowe nasycone", "kwasy nasycone",
    ),
    Nutrient.CARBOHYDRATES to listOf(
        "carbohydrates", "carbohydrate", "susivesikud", "oglhidrati",
        "hiilihydraatit", "hiilihydraattia", "angliavandeniai", "kohlenhydrate", "weglowodany",
    ),
    Nutrient.SUGARS to listOf(
        "sugars", "sugar", "suhkrud", "suhkruid", "cukuri", "cukuru",
        "sokerit", "sokereita", "cukrus", "cukru", "zucker", "cukry", "cukier",
    ),
    Nutrient.FIBRE to listOf(
        "dietary fibre", "dietary fiber", "fibre", "fiber", "kiudained", "kiudaineid",
        "skiedrvielas", "ravintokuitu", "kuitu", "skaidulines medziagos",
        "skaiduliniu medziagu", "ballaststoffe", "blonnik",
    ),
    Nutrient.PROTEIN to listOf(
        "proteins", "protein", "valk", "valgud", "olbaltumvielas",
        "proteiini", "proteiinia", "baltymai", "eiweiss", "bialko",
    ),
    Nutrient.SALT to listOf("salt", "sool", "sals", "suola", "druska", "salz", "sol"),
)
private val nutrientByName = nutrientNames.flatMap { (nutrient, names) ->
    names.map { name -> name to nutrient }
}.toMap()
private val namePattern = Regex(
    "(?<![\\p{L}])(?:" +
        nutrientByName.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
        ")(?![\\p{L}])"
)
private val valuePattern = Regex("""(?<![\p{L}\d.,+\-])([<>]?\s*\d+(?:[.,]\d+)?)\s*(kcal|kj|mg|g)\b""")
private val accentsPattern = Regex("\\p{M}+")
private val horizontalSpacePattern = Regex("[^\\S\\r\\n]+")

internal fun parseNutrition(text: String): Map<Nutrient, List<NutrientValue>> {
    val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(accentsPattern, "")
        .replace('\u0142', 'l')
        .replace("\u00df", "ss")
        .replace(horizontalSpacePattern, " ")
    val result = mutableMapOf<Nutrient, List<NutrientValue>>()

    for (line in normalized.lineSequence()) {
        val values = readValues(line)
        values.filter { it.unit == "kJ" }.takeIf { it.isNotEmpty() }?.let {
            result.putIfAbsent(Nutrient.ENERGY_KJ, it)
        }
        values.filter { it.unit == "kcal" }.takeIf { it.isNotEmpty() }?.let {
            result.putIfAbsent(Nutrient.ENERGY_KCAL, it)
        }

        val names = namePattern.findAll(line).toList()
        names.forEachIndexed { index, match ->
            val end = names.getOrNull(index + 1)?.range?.first ?: line.length
            val nutrientValues = readValues(line.substring(match.range.last + 1, end))
                .filter { it.unit == "g" || it.unit == "mg" }
            if (nutrientValues.isNotEmpty()) {
                result.putIfAbsent(nutrientByName.getValue(match.value), nutrientValues)
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