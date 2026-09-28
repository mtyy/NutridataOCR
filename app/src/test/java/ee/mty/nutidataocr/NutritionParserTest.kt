package ee.mty.nutidataocr

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionParserTest {
    @Test
    fun readsCommonNutrientsInSevenLanguages() {
        val labels = listOf(
            listOf("Fat", "of which saturates", "Carbohydrates", "of which sugars", "Fibre", "Protein", "Salt"),
            listOf("Rasvad", "millest k\u00fcllastunud rasvhapped", "S\u00fcsivesikud", "millest suhkrud", "Kiudained", "Valgud", "Sool"),
            listOf("Rasva", "millest k\u00fcllastunud rasvhappeid", "S\u00fcsivesikuid", "millest suhkruid", "Kiudaineid", "Valke", "Soola"),
            listOf("Tauki", "tostarp pies\u0101tin\u0101t\u0101s tauksk\u0101bes", "Og\u013chidr\u0101ti", "tostarp cukuri", "\u0160\u0137iedrvielas", "Olbaltumvielas", "S\u0101ls"),
            listOf("Rasva", "josta tyydyttynytt\u00e4", "Hiilihydraatit", "joista sokereita", "Ravintokuitu", "Proteiini", "Suola"),
            listOf("Riebalai", "i\u0161 kuri\u0173 so\u010diosios riebal\u0173 r\u016bg\u0161tys", "Angliavandeniai", "i\u0161 kuri\u0173 cukr\u0173", "Skaidulin\u0117s med\u017eiagos", "Baltymai", "Druska"),
            listOf("Fett", "davon ges\u00e4ttigte Fetts\u00e4uren", "Kohlenhydrate", "davon Zucker", "Ballaststoffe", "Eiwei\u00df", "Salz"),
            listOf("T\u0142uszcz", "w tym kwasy t\u0142uszczowe nasycone", "W\u0119glowodany", "w tym cukry", "B\u0142onnik", "Bia\u0142ko", "S\u00f3l"),
            listOf("Rasvu", "Kullastunud rasvhappeid", "Susivesikute", "Suhkru", "Kiudaine", "Valgu", "Soola"),
            listOf("Tauku", "Piesatinato taukskabju", "Oglhidratu", "Cukuru", "Skiedrvielu", "Olbaltumvielu", "Sali"),
            listOf("Rasvoja", "Tyydyttynytta", "Hiilihydraatteja", "Sokeria", "Kuitua", "Proteiinia", "Suolaa"),
            listOf("Riebalu", "Sociuju riebalu rugsciu", "Angliavandeniu", "Cukru", "Skaiduliniu medziagu", "Baltymu", "Druskos"),
            listOf("Fette", "Gesaettigte Fettsaeuren", "Kohlenhydraten", "Zucker", "Ballaststoffen", "Eiweisse", "Salz"),
            listOf("Tluszczu", "Kwasy nasycone", "Weglowodanow", "Cukrow", "Blonnika", "Bialka", "Soli"),
            listOf("Vet", "waarvan verzadigde vetzuren", "Koolhydraten", "waarvan suikers", "Voedingsvezel", "Eiwitten", "Zout"),
            listOf("Mati\u00e8res grasses", "dont acides gras satur\u00e9s", "Glucides", "dont sucres", "Fibres alimentaires", "Prot\u00e9ines", "Sel"),
            listOf("Fedt", "heraf m\u00e6ttede fedtsyrer", "Kulhydrat", "heraf sukkerarter", "Kostfibre", "Protein", "Salt"),
            listOf("Fett", "varav m\u00e4ttat fett", "Kolhydrat", "varav sockerarter", "Fiber", "Protein", "Salt"),
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
            val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").replace('\u0142', 'l').replace("\u00df", "ss")
            assertEquals(names.toString(), expected, parseNutrition(withoutAccents.uppercase()))
        }
    }

    @Test
    fun toleratesSmallOcrErrorsInLongNamesAcrossLanguages() {
        listOf("Carbohydates", "Susiveslkuid", "Oglhldrati", "Hiilihydraatlt",
            "Angliavandenial", "Kohlenhydratc", "Weglowodanv").forEach { name ->
            assertEquals(name, mapOf(Nutrient.CARBOHYDRATES to listOf(NutrientValue("12.5", "g"))),
                parseNutrition("$name 12,5 g"))
        }
        assertEquals(
            mapOf(
                Nutrient.SATURATES to listOf(NutrientValue("2", "g")),
                Nutrient.PROTEIN to listOf(NutrientValue("6", "g")),
                Nutrient.SUGARS to listOf(NutrientValue("1", "g")),
            ),
            parseNutrition("Saturatcd fat 2 g Pr0tein 6 g Suhkruld 1 g"),
        )
        assertEquals(mapOf(Nutrient.SATURATES to listOf(NutrientValue("2", "g"))),
            parseNutrition("Kullastunvd rasvhapped 2 g"))
        assertEquals(mapOf(Nutrient.PROTEIN to listOf(NutrientValue("6", "g"))),
            parseNutrition("Pro tein 6 g"))
        assertEquals(mapOf(Nutrient.SATURATES to listOf(NutrientValue("2", "g"))),
            parseNutrition("Saturatedfat 2 g"))
        assertEquals(mapOf(Nutrient.SATURATES to listOf(NutrientValue("2", "g"))),
            parseNutrition("Saturated-fat 2 g"))
    }

    @Test
    fun aliasesAreNormalizedUniqueAndAtLeastTwoEditsFromOtherNutrients() {
        val aliases = nutrientNames.flatMap { (nutrient, names) -> names.map { it to nutrient } }
        aliases.forEach { (name, _) -> assertEquals(name, normalizeLabelText(name), name) }
        val distance = org.apache.commons.text.similarity.LevenshteinDistance.getDefaultInstance()
        val collisions = aliases.flatMap { (name, nutrient) ->
            aliases.filter { (other, otherNutrient) ->
                otherNutrient != nutrient && name < other && distance.apply(name, other) < 2
            }.map { "$name ($nutrient) ~ ${it.first} (${it.second})" }
        }
        assertEquals(emptyList<String>(), collisions)
    }

    @Test
    fun doesNotFuzzShortWordsNumbersOrUnsupportedFatTypes() {
        listOf("Fast 2 g", "Sold 2 g", "Soolane 2 g", "Fatty 2 g", "Unsaturated fat 2 g",
            "Monounsaturated fat 2 g", "Polyunsaturated fats 2 g", "Trans fat 2 g",
            "Pr0tein six g", "Pr0tein 6 q", "Pr0tein\n6 g",
            "waarvan enkelvoudig onverzadigde vetten 2 g", "meervoudig onverzadigd vet 2 g", "transvet 2 g",
            "davon einfach unges\u00e4ttigte Fette 2 g", "mehrfach unges\u00e4ttigte Fette 2 g",
            "heraf enkeltum\u00e6ttede fedtsyrer 2 g", "flerum\u00e6ttede fedtsyrer 2 g",
            "varav enkelom\u00e4ttat fett 2 g", "flerom\u00e4ttat fett 2 g").forEach { text ->
            assertEquals(text, emptyMap<Nutrient, List<NutrientValue>>(), parseNutrition(text))
        }
        assertEquals(mapOf(Nutrient.FAT to listOf(NutrientValue("8", "g"))),
            parseNutrition("Fat 8 g Unsaturated fat 3 g"))
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