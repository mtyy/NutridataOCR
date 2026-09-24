package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionParserTest {
    @Test
    fun readsCommonNutrientsInSevenLanguages() {
        val labels = listOf(
            listOf("Fat", "of which saturates", "Carbohydrates", "of which sugars", "Fibre", "Protein", "Salt"),
            listOf("Rasvad", "millest k\u00fcllastunud rasvhapped", "S\u00fcsivesikud", "millest suhkrud", "Kiudained", "Valgud", "Sool"),
            listOf("Tauki", "tostarp pies\u0101tin\u0101t\u0101s tauksk\u0101bes", "Og\u013chidr\u0101ti", "tostarp cukuri", "\u0160\u0137iedrvielas", "Olbaltumvielas", "S\u0101ls"),
            listOf("Rasva", "josta tyydyttynytt\u00e4", "Hiilihydraatit", "joista sokereita", "Ravintokuitu", "Proteiini", "Suola"),
            listOf("Riebalai", "i\u0161 kuri\u0173 so\u010diosios riebal\u0173 r\u016bg\u0161tys", "Angliavandeniai", "i\u0161 kuri\u0173 cukr\u0173", "Skaidulin\u0117s med\u017eiagos", "Baltymai", "Druska"),
            listOf("Fett", "davon ges\u00e4ttigte Fetts\u00e4uren", "Kohlenhydrate", "davon Zucker", "Ballaststoffe", "Eiwei\u00df", "Salz"),
            listOf("T\u0142uszcz", "w tym kwasy t\u0142uszczowe nasycone", "W\u0119glowodany", "w tym cukry", "B\u0142onnik", "Bia\u0142ko", "S\u00f3l"),
        )
        val fields = listOf(
            Nutrient.FAT, Nutrient.SATURATES, Nutrient.CARBOHYDRATES,
            Nutrient.SUGARS, Nutrient.FIBRE, Nutrient.PROTEIN, Nutrient.SALT,
        )
        val expected = fields.mapIndexed { index, nutrient ->
            nutrient to listOf(NutrientValue("${index + 1}.5", "g"))
        }.toMap() + mapOf(
            Nutrient.ENERGY_KJ to listOf(NutrientValue("840", "kJ")),
            Nutrient.ENERGY_KCAL to listOf(NutrientValue("200", "kcal")),
        )

        labels.forEach { names ->
            val text = "840 kJ / 200 kcal\n" + names.mapIndexed { index, name ->
                "$name ${index + 1},5 g"
            }.joinToString("\n")
            assertEquals(names.toString(), expected, parseNutrition(text))
        }
    }

    @Test
    fun preservesColumnsUnitsAndComparisonsWithoutGuessingMissingValues() {
        assertEquals(
            mapOf(
                Nutrient.SATURATES to listOf(NutrientValue("2", "g")),
                Nutrient.PROTEIN to listOf(NutrientValue("<0.5", "g"), NutrientValue("1", "g")),
                Nutrient.SALT to listOf(NutrientValue("200", "mg")),
            ),
            parseNutrition("Saturated fat 2 g\nProtein / Valgud < 0,5 g 1 g 2%\nSalt 200 mg\nFat\n10 g"),
        )
        assertEquals(emptyMap<Nutrient, List<NutrientValue>>(), parseNutrition(""))
    }
}