package com.fanlens.prototype.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CategoryTaxonomy is a straight port of pc-catalogue-manager/app.js's
 * STANDARD_CATEGORIES/SPEC_TEMPLATES/SIZE_OPTIONS. These tests pin the exact
 * values that shipped there, so the two tools cannot silently drift apart --
 * see that file for the source of truth if this test ever needs updating.
 */
class CategoryTaxonomyTest {

    @Test
    fun theStandardCategoriesMatchThePcCatalogueManagerExactly() {
        assertEquals(
            listOf(
                "Fans", "Wall Fans", "Pedestal Fans", "Exhaust Fans", "Mixers", "Geysers",
                "AC", "LEDs", "Outdoor LEDs", "Fancy Lamps", "Wires/Cables", "Switches",
                "Distribution Box", "Stabilisers", "Induction Cooktops", "Kettles",
                "Immersion Rod Heaters", "Irons"
            ),
            CategoryTaxonomy.STANDARD_CATEGORIES
        )
    }

    @Test
    fun fansAndCoolerLikeCategoriesShowSizeNotWattage() {
        assertTrue(CategoryTaxonomy.hasSizeField("Fans"))
        assertFalse(CategoryTaxonomy.hasWattageField("Fans"))
        assertTrue(CategoryTaxonomy.hasSizeField("Wall Fans"))
        assertTrue(CategoryTaxonomy.hasSizeField("Pedestal Fans"))
        assertTrue(CategoryTaxonomy.hasSizeField("Exhaust Fans"))
    }

    @Test
    fun mixersIronsAndInductionCooktopsShowWattageNotSize() {
        // The exact examples from the bug report this taxonomy was built for.
        assertTrue(CategoryTaxonomy.hasWattageField("Mixers"))
        assertFalse(CategoryTaxonomy.hasSizeField("Mixers"))
        assertTrue(CategoryTaxonomy.hasWattageField("Irons"))
        assertFalse(CategoryTaxonomy.hasSizeField("Irons"))
        assertTrue(CategoryTaxonomy.hasWattageField("Induction Cooktops"))
        assertFalse(CategoryTaxonomy.hasSizeField("Induction Cooktops"))
    }

    @Test
    fun aCategoryCanLegitimatelyHaveBothDimensions() {
        // LEDs come in fixed tube lengths *and* have a wattage -- both fields
        // are genuinely useful here, so this is not the "indiscriminate" case
        // the bug report warned about.
        assertTrue(CategoryTaxonomy.hasSizeField("LEDs"))
        assertTrue(CategoryTaxonomy.hasWattageField("LEDs"))
        assertTrue(CategoryTaxonomy.hasSizeField("Immersion Rod Heaters"))
        assertTrue(CategoryTaxonomy.hasWattageField("Immersion Rod Heaters"))
    }

    @Test
    fun aCategoryWithNeitherDimensionShowsNeitherField() {
        assertFalse(CategoryTaxonomy.hasSizeField("Switches"))
        assertFalse(CategoryTaxonomy.hasWattageField("Switches"))
        assertFalse(CategoryTaxonomy.hasSizeField("Wires/Cables"))
        assertFalse(CategoryTaxonomy.hasWattageField("Wires/Cables"))
    }

    @Test
    fun categoryMatchingIsCaseAndWhitespaceInsensitive() {
        assertTrue(CategoryTaxonomy.hasWattageField("  mixers  "))
        assertTrue(CategoryTaxonomy.hasWattageField("MIXERS"))
    }

    @Test
    fun aNearDuplicateCategoryStillFindsTheClosestTemplate() {
        // "Ceiling Fans" isn't a key itself, but it contains "fans".
        assertEquals(CategoryTaxonomy.sizeOptionsFor("Fans"), CategoryTaxonomy.sizeOptionsFor("Ceiling Fans"))
    }

    @Test
    fun anUnknownOrBlankCategoryHasNoTemplateOrSizes() {
        assertNull(CategoryTaxonomy.templateFor(""))
        assertNull(CategoryTaxonomy.templateFor("Something Electric Emporium Has Never Stocked"))
        assertTrue(CategoryTaxonomy.sizeOptionsFor("").isEmpty())
        assertFalse(CategoryTaxonomy.hasSizeField(""))
        assertFalse(CategoryTaxonomy.hasWattageField(""))
    }

    @Test
    fun sizeOptionsAreTheMeasuredRealSizes() {
        assertEquals(listOf(600, 900, 1050, 1200, 1400), CategoryTaxonomy.sizeOptionsFor("Fans"))
        assertEquals(listOf(1000, 1500, 2000), CategoryTaxonomy.sizeOptionsFor("Immersion Rod Heaters"))
    }
}
