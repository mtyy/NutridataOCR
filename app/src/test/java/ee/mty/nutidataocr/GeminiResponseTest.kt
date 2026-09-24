package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiResponseTest {
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