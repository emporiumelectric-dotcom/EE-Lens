package com.fanlens.prototype.model

import com.fanlens.prototype.data.CatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftValidatorTest {

    private val valid = ProductDraft(brand = "Havells", name = "Stealth Air")

    @Test
    fun brandAndNameAreTheOnlyRequiredFields() {
        assertTrue(DraftValidator.validate(valid).isValid)
    }

    @Test
    fun aMissingBrandOrNameIsReported() {
        assertNotNull(DraftValidator.validate(valid.copy(brand = "  ")).brand)
        assertNotNull(DraftValidator.validate(valid.copy(name = "")).name)
        assertFalse(DraftValidator.validate(valid.copy(name = "")).isValid)
    }

    @Test
    fun anEmptyOptionalFieldIsNotAnError() {
        val errors = DraftValidator.validate(valid.copy(priceText = "", sizeSweep = ""))
        assertNull(errors.price)
        assertNull(errors.sizeSweep)
    }

    @Test
    fun aTypedPriceMustBeANumber() {
        assertNotNull(DraftValidator.validate(valid.copy(priceText = "four thousand")).price)
        assertNull(DraftValidator.validate(valid.copy(priceText = "4,250.50")).price)
    }

    @Test
    fun sweepIsCheckedForPlausibleMillimetres() {
        assertNotNull(DraftValidator.validate(valid.copy(sizeSweep = "0")).sizeSweep)
        assertNotNull(DraftValidator.validate(valid.copy(sizeSweep = "120000")).sizeSweep)
        assertNotNull(DraftValidator.validate(valid.copy(sizeSweep = "big")).sizeSweep)
        assertNull(DraftValidator.validate(valid.copy(sizeSweep = "1200")).sizeSweep)
    }

    @Test
    fun specsDropBlankKeysAndTrimTheRest() {
        val draft = valid.copy(
            specs = listOf(
                SpecRow(" Power ", " 40 W "),
                SpecRow("", "orphaned value"),
                SpecRow("Motor", "BLDC")
            )
        )
        assertEquals(mapOf("Power" to "40 W", "Motor" to "BLDC"), draft.specsMap())
    }

    @Test
    fun theWattageFieldFoldsIntoSpecsOnlyWhenTyped() {
        val withWattage = valid.copy(wattage = " 800 ")
        assertEquals("800", withWattage.specsMap()["Wattage"])

        val blankWattage = valid.copy(
            wattage = "",
            specs = listOf(SpecRow("Wattage", "600"))
        )
        // A blank dedicated field never erases a Wattage value entered as its
        // own spec row -- that row's own removal is what clears it.
        assertEquals("600", blankWattage.specsMap()["Wattage"])
    }

    @Test
    fun theDedicatedWattageFieldWinsOverAStaleSpecRow() {
        val draft = valid.copy(
            wattage = "1200",
            specs = listOf(SpecRow("Wattage", "600"))
        )
        assertEquals("1200", draft.specsMap()["Wattage"])
    }

    @Test
    fun editingAnExistingProductRoundTripsItsWattage() {
        val product = Product(
            id = "x", name = "N", model = "M", description = "D",
            specs = mapOf("Wattage" to "750")
        )
        assertEquals("750", ProductDraft.from(product).wattage)
        assertEquals("", ProductDraft.from(product.copy(specs = emptyMap())).wattage)
    }

    @Test
    fun editingAnExistingProductRoundTripsItsPrice() {
        val product = Product(
            id = "x", name = "N", model = "M", description = "D",
            priceMinor = 425_050, currency = "INR"
        )
        assertEquals("4250.50", ProductDraft.from(product).priceText)
        assertEquals("4250", ProductDraft.from(product.copy(priceMinor = 425_000)).priceText)
        assertEquals("", ProductDraft.from(product.copy(priceMinor = null)).priceText)
    }

    @Test
    fun anExistingProductIsNotTreatedAsNew() {
        assertTrue(ProductDraft().isNew)
        assertFalse(ProductDraft(id = "abc").isNew)
    }
}

class SlugTest {

    @Test
    fun slugsAreDerivedFromBrandAndName() {
        assertEquals(
            "havells-stealth-air",
            CatalogRepository.slugify("Havells", "Stealth Air", "fallback")
        )
    }

    @Test
    fun punctuationAndRepeatedSpacingCollapse() {
        assertEquals(
            "atomberg-aris-contour-smart",
            CatalogRepository.slugify("Atomberg", "Aris  Contour — Smart!", "fallback")
        )
    }

    @Test
    fun anUnslugabbleNameFallsBackToTheId() {
        assertEquals("fallback-id", CatalogRepository.slugify("···", "!!!", "fallback-id"))
    }
}
