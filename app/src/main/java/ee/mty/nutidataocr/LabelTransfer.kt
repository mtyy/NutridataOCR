package ee.mty.nutidataocr

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

internal const val LABEL_TRANSFER_EXTRA = "label_transfer"
internal const val DRAFT_TOKEN_EXTRA = "draft_token"

internal val LABEL_COMPONENTS = linkedMapOf(
    Nutrient.FAT to 3,
    Nutrient.SATURATES to 10,
    Nutrient.CARBOHYDRATES to 42,
    Nutrient.SUGARS to 85,
    Nutrient.PROTEIN to 2,
    Nutrient.SALT to 84,
)

internal data class LabelTransfer(val amounts: Map<Int, BigDecimal>, val manualIds: Set<Int>) {
    fun toJson(): String = JSONObject().apply {
        put("amounts", JSONObject().apply {
            amounts.forEach { (component, amount) -> put(component.toString(), amount) }
        })
        put("sources", JSONObject().apply {
            amounts.keys.forEach { component ->
                put(component.toString(), if (component in manualIds) "manual" else "confirmed_ocr")
            }
        })
    }.toString()
}

internal fun reviewLabel(
    values: Map<Nutrient, List<NutrientValue>>,
    manual: Set<Nutrient>,
): LabelTransfer {
    val amounts = LABEL_COMPONENTS.map { (nutrient, component) ->
        val value = values[nutrient]?.singleOrNull()
        val number = value?.amount?.replace(',', '.')?.toBigDecimalOrNull()
        require(number != null && number >= BigDecimal.ZERO && value.amount.length <= 64) {
            "Review all six fields. Replace missing values, multiple columns and < or > amounts with a numeric estimate."
        }
        val grams = when (value.unit) {
            "g" -> number
            "mg" -> number.movePointLeft(3)
            else -> throw IllegalArgumentException("Use grams or milligrams for all six fields.")
        }
        val per100 = grams.setScale(3, RoundingMode.HALF_UP)
        require(per100 <= BigDecimal(100)) { "A nutrient cannot exceed 100 g per 100 g." }
        component to per100
    }.toMap()
    require(amounts.getValue(10) <= amounts.getValue(3)) { "Saturated fat exceeds fat. Correct the label values." }
    require(amounts.getValue(85) <= amounts.getValue(42)) { "Sugars exceed carbohydrates. Correct the label values." }
    require(listOf(3, 42, 2, 84).sumOf { amounts.getValue(it) } <= BigDecimal(100)) {
        "Fat, carbohydrates, protein and salt exceed 100 g per 100 g. Correct the label values."
    }
    return LabelTransfer(amounts, manual.mapNotNull(LABEL_COMPONENTS::get).toSet())
}