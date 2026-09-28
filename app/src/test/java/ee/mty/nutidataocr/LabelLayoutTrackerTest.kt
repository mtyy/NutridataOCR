package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LabelLayoutTrackerTest {
    private fun word(text: String, horizontal: Double, vertical: Double, confidence: Float = 0.96f) =
        OcrLine(text, confidence = confidence, tokens = listOf(
            OcrToken(text, confidence, OcrBox(horizontal, vertical, text.length * 10.0, 20.0)),
        ))

    private fun anchors() = listOf(
        word("Nutrition", 230.0, 10.0), word("Typical", 230.0, 130.0), word("Values", 330.0, 130.0),
    )

    private fun moved(lines: List<OcrLine>, scale: Double = 1.0, angle: Double = 0.0) = lines.map { line ->
        line.copy(tokens = line.tokens.map { token ->
            val box = token.box!!
            token.copy(box = box.copy(
                centerX = scale * (cos(angle) * box.centerX - sin(angle) * box.centerY) + 80,
                centerY = scale * (sin(angle) * box.centerX + cos(angle) * box.centerY) + 45,
                width = box.width * scale, height = box.height * scale, angle = box.angle + angle,
            ))
        })
    }

    @Test
    fun reconstructsAcrossPanZoomAndRotationWithoutReplayingCachedEvidence() {
        val tracker = LabelLayoutTracker()
        val left = anchors() + word("Fat", 40.0, 60.0)
        val right = anchors() + word("8g", 480.0, 60.0)
        tracker.observe(left, 1000)
        tracker.observe(moved(right, 1.6, 0.12), 1300)
        assertEquals(1, tracker.fragmentCount)
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
        repeat(5) { index -> tracker.observe(moved(right), 1600 + index * 300L) }
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
        tracker.observe(left, 4000)
        tracker.observe(left, 4300)
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
    }

    @Test
    fun newEvidenceCorrectsWordsAndDigitsAndWithdrawsReadiness() {
        val tracker = LabelLayoutTracker()
        val wrong = anchors() + word("Fxt", 40.0, 60.0) + word("8g", 480.0, 60.0)
        repeat(3) { index -> tracker.observe(wrong, 1000 + index * 300L) }
        val corrected = anchors() + word("Fat", 40.0, 60.0) + word("9g", 480.0, 60.0)
        tracker.observe(corrected, 1900)
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
        repeat(8) { index -> tracker.observe(corrected, 2200 + index * 300L) }
        assertEquals(listOf(NutrientValue("9", "g")), tracker.nutrients[Nutrient.FAT])
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
        assertFalse(tracker.words.any { it.text == "Fxt" || it.text == "8g" })
        val conflict = anchors() + word("Fat", 40.0, 60.0) + word("6g", 480.0, 60.0)
        repeat(2) { index -> tracker.observe(conflict, 5000 + index * 300L) }
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
    }

    @Test
    fun completedValuesSurviveUnrelatedFragmentsAndTheirEviction() {
        val tracker = LabelLayoutTracker()
        val fat = anchors() + word("Fat", 40.0, 60.0) + word("8g", 480.0, 60.0)
        repeat(3) { index -> tracker.observe(fat, 1000 + index * 300L) }
        tracker.observe(listOf(word("Protein", 40.0, 60.0), word("6g", 480.0, 60.0)), 1900)
        repeat(4) { index -> tracker.observe(listOf(word("Ingredients", 200.0, 40.0)), 2200 + index * 300L) }
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
        assertEquals(listOf(NutrientValue("6", "g")), tracker.nutrients[Nutrient.PROTEIN])
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
        assertFalse(Nutrient.PROTEIN in tracker.stableNutrients)
        assertEquals(3, tracker.fragmentCount)
        tracker.observe(listOf(word("Fat", 40.0, 60.0), word("9g", 480.0, 60.0)), 3400)
        assertEquals(listOf(NutrientValue("9", "g")), tracker.nutrients[Nutrient.FAT])
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
        tracker.reset(4000)
        assertTrue(tracker.nutrients.isEmpty())
        assertTrue(tracker.stableNutrients.isEmpty())
    }

    @Test
    fun partiallyReadSourceRetainsItsValueButMustEarnReadinessAgain() {
        val tracker = LabelLayoutTracker()
        val complete = anchors() + word("Fat", 40.0, 60.0) + word("8g", 480.0, 60.0)
        repeat(3) { index -> tracker.observe(complete, 1000 + index * 300L) }
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
        val missingUnit = anchors() + word("Fat", 40.0, 60.0) + word("8", 480.0, 60.0)
        repeat(3) { index -> tracker.observe(missingUnit, 1900 + index * 300L) }
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
        repeat(5) { index -> tracker.observe(complete, 2800 + index * 300L) }
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
    }

    @Test
    fun unmatchedViewsStaySeparateAndFragmentsAreBounded() {
        val tracker = LabelLayoutTracker()
        tracker.observe(anchors() + word("Fat", 40.0, 60.0), 1000)
        repeat(5) { index -> tracker.observe(listOf(word("8g", 480.0, 60.0)), 1300 + index * 300L) }
        assertEquals(3, tracker.fragmentCount)
        assertFalse(Nutrient.FAT in tracker.nutrients)
        assertTrue(tracker.stableNutrients.isEmpty())
    }

    @Test
    fun joinsWrappedNutrientNames() {
        val tracker = LabelLayoutTracker()
        tracker.observe(listOf(
            word("Saturated", 60.0, 30.0), word("fat", 40.0, 60.0), word("2.5g", 480.0, 60.0),
        ), 1000)
        assertEquals(listOf(NutrientValue("2.5", "g")), tracker.nutrients[Nutrient.SATURATES])
        assertFalse(Nutrient.FAT in tracker.nutrients)
    }

    @Test
    fun clearerReadingsOutweighRepeatedLowConfidenceDigits() {
        val tracker = LabelLayoutTracker()
        val weak = anchors() + word("Fat", 40.0, 60.0) + word("8g", 480.0, 60.0, 0.4f)
        repeat(6) { index -> tracker.observe(weak, 1000 + index * 300L) }
        val clear = anchors() + word("Fat", 40.0, 60.0) + word("9g", 480.0, 60.0, 0.99f)
        tracker.observe(clear, 3000)
        tracker.observe(clear, 3300)
        assertEquals(listOf(NutrientValue("9", "g")), tracker.nutrients[Nutrient.FAT])
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
        tracker.observe(clear, 3600)
        assertTrue(Nutrient.FAT in tracker.stableNutrients)
    }

    @Test
    fun oneIncorrectAnchorDoesNotMoveTheOtherRows() {
        val tracker = LabelLayoutTracker()
        tracker.observe(anchors() + word("Average", 60.0, 220.0) + word("Fat", 40.0, 60.0), 1000)
        tracker.observe(moved(anchors() + word("Average", 800.0, 400.0) + word("8g", 480.0, 60.0)), 1300)
        assertEquals(1, tracker.fragmentCount)
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
    }

    @Test
    fun splitAndJoinedTokensReplaceOldCellsWithoutDuplicatingNumbers() {
        val tracker = LabelLayoutTracker()
        val joined = anchors() + word("Fat", 40.0, 60.0) + word("8g", 480.0, 60.0)
        tracker.observe(joined, 1000)
        val split = anchors() + word("Fat", 40.0, 60.0) + word("8", 474.0, 60.0) + word("g", 486.0, 60.0)
        tracker.observe(split, 1300)
        assertFalse(tracker.words.any { it.text == "8g" })
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
        tracker.observe(joined, 1600)
        assertFalse(tracker.words.any { it.text == "8" || it.text == "g" })
        assertEquals(listOf(NutrientValue("8", "g")), tracker.nutrients[Nutrient.FAT])
        assertFalse(Nutrient.FAT in tracker.stableNutrients)
    }

    @Test
    fun extraUnitlessColumnIsNotSilentlyDiscarded() {
        val tracker = LabelLayoutTracker()
        val frame = anchors() + word("Fat", 40.0, 60.0) + word("8", 350.0, 60.0) + word("4g", 480.0, 60.0)
        repeat(3) { index -> tracker.observe(frame, 1000 + index * 300L) }
        assertFalse(Nutrient.FAT in tracker.nutrients)
        assertTrue(tracker.stableNutrients.isEmpty())
    }

    @Test
    fun optionalModeKeepsOverridesAndIsolatesOcrEvidenceAcrossSwitches() {
        val model = OcrViewModel()
        model.setManualNutrient(Nutrient.SALT, "0.5")
        model.importGeminiColumn(GeminiColumn("per 100 g", listOf(GeminiNutrient("protein", "6", "g"))))
        val frame = anchors() + word("Fat", 40.0, 60.0) + word("8g", 480.0, 60.0)
        model.onTextRecognized(frame, 1000)
        assertFalse(model.layoutEnabled)
        assertFalse(Nutrient.FAT in model.nutrients)
        model.setLayoutEnabled(true, 1100)
        model.onTextRecognized(frame, 1000, isPhoto = true)
        assertTrue(model.layoutWords.isEmpty())
        repeat(3) { index -> model.onTextRecognized(frame, 1200 + index * 300L) }
        assertTrue(Nutrient.FAT in model.readyNutrients)
        assertEquals(listOf(NutrientValue("8", "g")), model.nutrients[Nutrient.FAT])
        model.setLayoutEnabled(false, 2000)
        assertTrue(model.layoutWords.isEmpty())
        assertTrue(model.nutrients.isEmpty())
        assertEquals(setOf(Nutrient.SALT, Nutrient.PROTEIN), model.readyNutrients)
        model.onTextRecognized(listOf(OcrLine("Fat 9 g")), 2300)
        assertEquals(listOf(NutrientValue("9", "g")), model.nutrients[Nutrient.FAT])
        model.reset(2500)
        assertTrue(model.readyNutrients.isEmpty())
    }

    @Test
    fun joinsSeparateBlocksWithoutMixingRowsOrDroppingColumns() {
        val tracker = LabelLayoutTracker()
        val result = tracker.observe(listOf(
            word("Fat", 40.0, 30.0), word("8", 400.0, 30.0), word("g", 425.0, 30.0),
            word("Protein", 60.0, 70.0), word("12g", 400.0, 70.0), word("6g", 510.0, 70.0),
        ), 1000)
        val parsed = parseNutrition(result.joinToString("\n") { it.text })
        assertEquals(listOf(NutrientValue("8", "g")), parsed[Nutrient.FAT])
        assertEquals(listOf(NutrientValue("12", "g"), NutrientValue("6", "g")), parsed[Nutrient.PROTEIN])
        assertTrue(tracker.observe(emptyList(), 1000).isEmpty())
    }

    @Test
    fun missingGeometryKeepsOrdinaryOcrAndResetRejectsOldFrames() {
        val tracker = LabelLayoutTracker()
        val lines = listOf(OcrLine("Salt 0.5 g"))
        assertEquals(lines, tracker.observe(lines, 1000))
        tracker.reset(2000)
        assertTrue(tracker.words.isEmpty())
        assertTrue(tracker.observe(lines, 1900, isPhoto = true).isEmpty())
    }
}