package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionScanTest {
    @Test
    fun unidentifiedSuggestionsNormalizeUnitsAndExcludeAssignedOrNonNutrientValues() {
        val model = OcrViewModel()
        model.setManualNutrient(Nutrient.SALT, "0.25")
        model.importGeminiColumn(GeminiColumn("per 100 g", listOf(GeminiNutrient("protein", "6", "g"))))
        val tokens = listOf("8g", "250mg", "6g", "500mg", "0,50g", "12", "0g", "200kcal", "840kJ", "150g", "10%")
            .map { OcrToken(it, 0.99f) } + OcrToken("9g", 0.4f)
        model.onTextRecognized(listOf(OcrLine("Fat 8 g"), OcrLine("", tokens = tokens)), 1000)
        assertEquals(listOf("0.5", "12", "0"), model.unidentifiedAmounts)
        model.setManualNutrient(Nutrient.SUGARS, "0.5")
        assertEquals(listOf("12", "0"), model.unidentifiedAmounts)
        model.reset(2000)
        assertTrue(model.unidentifiedAmounts.isEmpty())
    }

    @Test
    fun estonianSaturatedFatVariantsBecomeReadyWithoutReplacingTotalFat() {
        listOf(
            "k\u00fcllastunud rasvhapped",
            "kullastunud rasvhapped",
            "k\u00fcllastunudrasvhapped",
            "k\u00fclastunud rasvhapped",
            "millest k\u00fcllastunud rasvhappeid",
        ).forEach { label ->
            val model = OcrViewModel()
            val lines = listOf(OcrLine("Rasva 8 g", confidence = 0.95f), OcrLine("$label 2,5 g", confidence = 0.95f))
            repeat(3) { index -> model.onTextRecognized(lines, 1000 + index * 300L) }
            assertEquals(label, listOf(NutrientValue("2.5", "g")), model.effectiveNutrients[Nutrient.SATURATES])
            assertEquals(label, listOf(NutrientValue("8", "g")), model.effectiveNutrients[Nutrient.FAT])
            assertTrue(label, Nutrient.SATURATES in model.readyNutrients)
        }
    }

    @Test
    fun manualMissingValueCompletesTheChecklistWithoutBecomingAnOcrReading() {
        val model = OcrViewModel()
        val lines = listOf("Fat 8 g", "Saturates 2 g", "Carbohydrates 12 g", "Sugars 0 g", "Protein 6 g")
            .map { OcrLine(it, confidence = 0.95f) }
        repeat(3) { index -> model.onTextRecognized(lines, 1_000 + index * 300L) }
        assertEquals(5, model.readyNutrients.size)
        assertTrue(model.setManualNutrient(Nutrient.SALT, " 0,50 "))
        assertEquals(REQUIRED_SCAN_NUTRIENTS.toSet(), model.readyNutrients)
        assertEquals(listOf(NutrientValue("0.5", "g")), model.effectiveNutrients[Nutrient.SALT])
        assertFalse(Nutrient.SALT in model.stableNutrients)
        assertFalse(Nutrient.SALT in model.nutrients)
        model.useOcr(Nutrient.SALT)
        assertEquals(5, model.readyNutrients.size)
        assertFalse(Nutrient.SALT in model.effectiveNutrients)
    }

    @Test
    fun scanningNeverOverwritesManualValuesAndResetClearsOverrides() {
        val model = OcrViewModel()
        assertTrue(model.setManualNutrient(Nutrient.FAT, "0"))
        repeat(4) { index ->
            model.onTextRecognized(listOf(OcrLine("Fat 8 g", confidence = 0.95f)), 1_000 + index * 300L)
        }
        assertEquals(listOf(NutrientValue("0", "g")), model.effectiveNutrients[Nutrient.FAT])
        assertEquals(listOf(NutrientValue("8", "g")), model.nutrients[Nutrient.FAT])
        model.useOcr(Nutrient.FAT)
        assertEquals(listOf(NutrientValue("8", "g")), model.effectiveNutrients[Nutrient.FAT])
        assertTrue(model.setManualNutrient(Nutrient.SALT, "< 0,5"))
        assertEquals(NutrientValue("<0.5", "g"), model.manualNutrients[Nutrient.SALT])
        model.reset(3_000)
        assertTrue(model.manualNutrients.isEmpty())
        assertTrue(model.readyNutrients.isEmpty())
    }

    @Test
    fun manualEntryNormalizesUnitsAndRejectsInvalidValuesWithoutLosingTheOverride() {
        val model = OcrViewModel()
        model.onTextRecognized(listOf(OcrLine("Salt 500 mg", confidence = 0.95f)), 1_000)
        assertEquals("0.5", model.manualEntryAmount(Nutrient.SALT))
        model.onTextRecognized(listOf(OcrLine("Protein 12 g 6 g", confidence = 0.95f)), 1_300)
        assertEquals("", model.manualEntryAmount(Nutrient.PROTEIN))
        assertTrue(model.setManualNutrient(Nutrient.SALT, ".75"))
        listOf("", "-1", "NaN", "Infinity", "1e3", "1,2,3", "2 g", "0.", "9".repeat(65)).forEach { input ->
            assertFalse(input, model.setManualNutrient(Nutrient.SALT, input))
            assertEquals(NutrientValue("0.75", "g"), model.manualNutrients[Nutrient.SALT])
        }
        assertFalse(model.setManualNutrient(Nutrient.ENERGY_KCAL, "100"))
        assertEquals("0.75", model.manualEntryAmount(Nutrient.SALT))
    }

    @Test
    fun stabilityNeedsThreeClearIndependentReadings() {
        val scan = NutritionScan()
        val reading = listOf(OcrLine("Fat 8.0 g", confidence = 0.95f))
        scan.observe(reading, 1_000)
        scan.observe(reading, 1_100)
        scan.observe(reading, 1_300)
        assertTrue(scan.stableNutrients.isEmpty())
        scan.observe(listOf(OcrLine("Fat 8.00 g", confidence = 0.95f)), 1_400, isPhoto = true)
        assertEquals(setOf(Nutrient.FAT), scan.stableNutrients)
        scan.observe(emptyList(), 60_000)
        assertEquals(setOf(Nutrient.FAT), scan.stableNutrients)

        scan.reset(61_000)
        scan.observe(reading, 61_100, isPhoto = true)
        repeat(5) { scan.observe(reading, 61_100, isPhoto = true) }
        assertTrue(scan.stableNutrients.isEmpty())
    }

    @Test
    fun lowConfidenceAndMultipleColumnsNeverCountAsStable() {
        val scan = NutritionScan()
        repeat(12) { index ->
            scan.observe(listOf(
                OcrLine("Fat 8 g", confidence = 0.84f, textHeightPx = 100f),
                OcrLine("Protein 12 g 6 g", confidence = 0.99f),
                OcrLine("Salt 1 g", confidence = Float.NaN),
            ), index * 300L)
        }
        assertEquals(3, scan.nutrients.size)
        assertTrue(scan.stableNutrients.isEmpty())
    }

    @Test
    fun conflictingReadingsWithdrawStabilityUntilAValueSettlesAgain() {
        val scan = NutritionScan()
        repeat(3) { index ->
            scan.observe(listOf(OcrLine("Fat 8 g", confidence = 0.95f)), index * 300L)
        }
        assertEquals(setOf(Nutrient.FAT), scan.stableNutrients)
        repeat(2) { index ->
            scan.observe(listOf(OcrLine("Fat 9 g", confidence = 0.95f)), 900 + index * 300L)
        }
        assertTrue(scan.stableNutrients.isEmpty())
        repeat(6) { index ->
            scan.observe(listOf(OcrLine("Fat 9 g", confidence = 0.95f)), 1_500 + index * 300L)
        }
        assertEquals(listOf(NutrientValue("9", "g")), scan.nutrients[Nutrient.FAT])
        assertEquals(setOf(Nutrient.FAT), scan.stableNutrients)
    }

    @Test
    fun modelCollectsAllSixRequiredFieldsIncludingZeroAndResetClearsReadiness() {
        val model = OcrViewModel()
        val lines = listOf("Fat 8 g", "Saturates 2 g", "Carbohydrates 12 g", "Sugars 0 g", "Protein 6 g", "Salt 0.5 g")
            .map { OcrLine(it, confidence = 0.95f) }
        repeat(3) { index -> model.onTextRecognized(lines, 1_000 + index * 300L) }
        assertEquals(REQUIRED_SCAN_NUTRIENTS.toSet(), model.stableNutrients)
        model.reset(2_000)
        model.onTextRecognized(lines, 1_900, isPhoto = true)
        assertTrue(model.stableNutrients.isEmpty())
    }

    @Test
    fun liveFramesAndPhotosAccumulateThroughBothPipelinesAndResetRejectsLateResults() {
        val model = OcrViewModel()
        val fat = OcrLine(
            text = "Fat 8 g",
            confidence = 0.99f,
            textHeightPx = 96f,
            tokens = listOf(OcrToken("Fat", 0.99f), OcrToken("8g", 0.99f)),
        )

        model.onTextRecognized(listOf(fat), 1_000)
        assertEquals(1, model.numericFallback.numbers.single().samples)
        model.onTextRecognized(listOf(fat), 1_100, isPhoto = true)
        assertEquals(2, model.numericFallback.numbers.single().samples)
        model.onTextRecognized(listOf(fat), 1_100, isPhoto = true)
        model.onTextRecognized(listOf(fat), 1_200)
        assertEquals(2, model.numericFallback.numbers.single().samples)

        val correctedFat = fat.copy(
            text = "Fat 9 g",
            tokens = listOf(OcrToken("Fat", 0.99f), OcrToken("9g", 0.99f)),
        )
        model.onTextRecognized(listOf(correctedFat), 1_500)
        assertEquals(listOf(NutrientValue("8", "g")), model.nutrients[Nutrient.FAT])
        model.onTextRecognized(listOf(correctedFat), 1_600, isPhoto = true)
        model.onTextRecognized(listOf(correctedFat), 1_700, isPhoto = true)
        assertEquals(listOf(NutrientValue("9", "g")), model.nutrients[Nutrient.FAT])

        model.onTextRecognized(listOf(OcrLine("Protein 12 g")), 6_000, isPhoto = true)
        model.onTextRecognized(emptyList(), 11_000)
        assertEquals(
            mapOf(
                Nutrient.FAT to listOf(NutrientValue("9", "g")),
                Nutrient.PROTEIN to listOf(NutrientValue("12", "g")),
            ),
            model.nutrients,
        )
        assertEquals(
            mapOf(NutrientValue("8", "g") to 2, NutrientValue("9", "g") to 3),
            model.numericFallback.numbers.associate { it.number to it.samples },
        )

        model.reset(12_000)
        model.onTextRecognized(listOf(fat), 11_999, isPhoto = true)
        assertTrue(model.nutrients.isEmpty())
        assertEquals(NumericFallbackResult(), model.numericFallback)
        assertEquals("", model.recognizedText)
    }

    @Test
    fun keepsNutrientsAcrossPartialAndUnreadableFramesAndResetsForNextProduct() {
        val scan = NutritionScan()
        scan.observe(listOf(OcrLine("Fat 8 g")), 0)
        scan.observe(listOf(OcrLine("Protein 12 g")), 100)
        scan.observe(listOf(OcrLine("Fat x g")), 600)
        scan.observe(emptyList(), 60_000)

        assertEquals(
            mapOf(
                Nutrient.FAT to listOf(NutrientValue("8", "g")),
                Nutrient.PROTEIN to listOf(NutrientValue("12", "g")),
            ),
            scan.nutrients,
        )
        scan.reset(61_000)
        scan.observe(listOf(OcrLine("Fat 8 g")), 60_999)
        assertTrue(scan.nutrients.isEmpty())
        scan.observe(listOf(OcrLine("Salt 1 g")), 61_100)
        assertEquals(mapOf(Nutrient.SALT to listOf(NutrientValue("1", "g"))), scan.nutrients)
    }

    @Test
    fun repeatedClearerReadingCorrectsOldValueButOneLargeOutlierDoesNot() {
        val scan = NutritionScan()
        repeat(20) { index ->
            scan.observe(listOf(OcrLine("126 kcal", confidence = 0.55f, textHeightPx = 12f)), index * 300L)
        }
        val clearerReading = listOf(OcrLine("125 kcal", confidence = 0.99f, textHeightPx = 36f))
        scan.observe(clearerReading, 6_000)
        assertEquals(listOf(NutrientValue("126", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
        scan.observe(clearerReading, 6_300)
        assertEquals(listOf(NutrientValue("125", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
        scan.observe(listOf(OcrLine("725 kcal", confidence = 1f, textHeightPx = 200f)), 6_600)
        assertEquals(listOf(NutrientValue("125", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
    }

    @Test
    fun majorityWinsDespiteFormattingChangesFlickerAndRapidDuplicateFrames() {
        val scan = NutritionScan()
        repeat(5) { index ->
            scan.observe(listOf(OcrLine("Protein 8.0 g")), index * 300L)
        }
        repeat(8) { index ->
            val timestamp = 1_500 + index * 600L
            scan.observe(listOf(OcrLine("Protein 6 g")), timestamp)
            repeat(10) { duplicate ->
                scan.observe(listOf(OcrLine("Protein 6 g")), timestamp + duplicate + 1)
            }
            assertEquals(listOf(NutrientValue("8", "g")), scan.nutrients[Nutrient.PROTEIN])
            scan.observe(listOf(OcrLine("Protein 8.00 g")), timestamp + 300)
        }
        assertEquals(listOf(NutrientValue("8", "g")), scan.nutrients[Nutrient.PROTEIN])
    }
}