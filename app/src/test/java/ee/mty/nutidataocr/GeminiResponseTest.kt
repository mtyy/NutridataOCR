package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiResponseTest {
    @Test
    fun importsSixFieldsWithoutOverwritingManualCorrectionsOrBecomingOcrEvidence() {
        val response = parseGeminiResponse("""{"columns":[{"basis":"per 100 g","nutrients":[
            {"name":"fat","amount":8,"unit":"g"},
            {"name":"saturates","amount":2,"unit":"g"},
            {"name":"carbohydrates","amount":12,"unit":"g"},
            {"name":"sugars","amount":1,"unit":"g"},
            {"name":"protein","amount":6,"unit":"g"},
            {"name":"salt","amount":500,"unit":"mg"}]}]}""")
        val model = OcrViewModel()
        model.setManualNutrient(Nutrient.FAT, "9")
        assertTrue(model.importGemini(response))
        assertEquals(REQUIRED_SCAN_NUTRIENTS.toSet(), model.readyNutrients)
        assertEquals(listOf(NutrientValue("9", "g")), model.effectiveNutrients[Nutrient.FAT])
        assertEquals("0.5", model.manualEntryAmount(Nutrient.SALT))
        repeat(3) { index ->
            model.onTextRecognized(listOf(OcrLine("Salt 2 g", confidence = 0.95f)), 1000 + index * 300L)
        }
        assertEquals("0.5", model.manualEntryAmount(Nutrient.SALT))
        assertFalse(Nutrient.PROTEIN in model.stableNutrients)
        val label = reviewLabel(model.effectiveNutrients, model.manualNutrients.keys, model.geminiNutrients.keys)
        assertEquals(6, label.amounts.size)
        val sources = org.json.JSONObject(label.toJson()).getJSONObject("sources")
        assertEquals("confirmed_gemini", sources.getString("84"))
        assertEquals("manual", sources.getString("3"))
        model.useOcr(Nutrient.SALT)
        assertEquals("2.0", model.manualEntryAmount(Nutrient.SALT))
        model.reset(3000)
        assertTrue(model.geminiNutrients.isEmpty())
        assertTrue(model.effectiveNutrients.isEmpty())
    }

    @Test
    fun selectsPer100gColumnAndRequiresAChoiceForAmbiguousReplies() {
        val model = OcrViewModel()
        val serving = GeminiColumn("per serving", listOf(GeminiNutrient("fat", "4", "g")))
        val per100 = GeminiColumn("per 100g", listOf(GeminiNutrient("fat", "8", "g")))
        assertTrue(model.importGemini(GeminiNutrition(listOf(serving, per100), emptyList())))
        assertEquals("8.0", model.manualEntryAmount(Nutrient.FAT))
        assertFalse(model.importGemini(GeminiNutrition(listOf(serving, serving), emptyList())))
        assertEquals("8.0", model.manualEntryAmount(Nutrient.FAT))
        model.importGeminiColumn(GeminiColumn(null, listOf(
            GeminiNutrient("salt", "< 500", "mg"),
            GeminiNutrient("protein", "3", "g"), GeminiNutrient("protein", "6", "g"),
        )))
        assertEquals("<0.5", model.manualEntryAmount(Nutrient.SALT))
        assertEquals(2, model.effectiveNutrients[Nutrient.PROTEIN]?.size)
        assertFalse(Nutrient.PROTEIN in model.readyNutrients)
        assertFalse(Nutrient.FAT in model.geminiNutrients)
    }

    @Test
    fun extractsNutritionFromConversationAndFencesPreservingColumns() {
        val input = """
            Here is the result {not JSON}. {"other":"object"}
            ```json
            {"columns":[
              {"basis":"per 100 g","nutrients":[
                {"name":"energy","amount":420,"unit":"kJ"},
                {"name":"energy","amount":100,"unit":"kcal"},
                {"name":"fat","amount":"<0.5","unit":"g"}]},
              {"basis":null,"nutrients":[{"name":"salt","amount":"0,2","unit":"g"}]}
            ],"uncertain":["Unclear {protein} and \"sugars\""]}
            ```
            These values are approximate.
        """.trimIndent()
        val result = parseGeminiResponse(input)
        assertEquals(2, result.columns.size)
        assertEquals("per 100 g", result.columns[0].basis)
        assertEquals(listOf("420", "100", "<0.5"), result.columns[0].nutrients.map { it.amount })
        assertEquals(null, result.columns[1].basis)
        assertEquals("0,2", result.columns[1].nutrients.single().amount)
        assertEquals(listOf("Unclear {protein} and \"sugars\""), result.uncertain)
    }

    @Test
    fun rejectsMissingTruncatedOrInvalidNutritionJson() {
        listOf(
            "There are 10 grams of fat.",
            "{\"columns\":[",
            "{\"columns\":\"wrong type\"}",
            "{\"columns\":[{\"nutrients\":[{\"name\":\"fat\",\"amount\":-1,\"unit\":\"g\"}]}]}",
            "{\"columns\":[{\"nutrients\":[{\"name\":\"fat\",\"amount\":1,\"unit\":\"kcal\"}]}]}",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parseGeminiResponse(input) }
        }
    }

    @Test
    fun acceptsEmptyExtractionWithoutInventingValues() {
        val result = parseGeminiResponse("{\"columns\":[],\"uncertain\":[\"No readable table\"]}")
        assertEquals(emptyList<GeminiColumn>(), result.columns)
        assertEquals(listOf("No readable table"), result.uncertain)
    }
}