package com.fanlens.prototype.ui

import com.fanlens.prototype.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers filterProductsByCategory and productListCountLabel -- the Products
 * list's category filter and its "X of Y products" indicator. Pure and
 * dependency-free (no Compose, no database), mirroring
 * pc-catalogue-manager/app.js's matches()/refreshStats(), which this is
 * meant to behave the same as.
 */
class ElectricEmporiumScreenTest {

    private fun product(id: String, category: String? = null) = Product(
        id = id,
        name = "Product $id",
        model = "",
        description = "",
        category = category
    )

    @Test
    fun blankCategoryReturnsEveryProductUnchanged() {
        val products = listOf(product("1", "Fans"), product("2", "Geysers"), product("3", null))

        val result = filterProductsByCategory(products, "")

        assertSame(products, result)
    }

    @Test
    fun onlyProductsMatchingTheCategorySurvive() {
        val fan1 = product("1", "Fans")
        val fan2 = product("2", "Fans")
        val geyser = product("3", "Geysers")

        val result = filterProductsByCategory(listOf(fan1, fan2, geyser), "Fans")

        assertEquals(listOf(fan1, fan2), result)
    }

    @Test
    fun categoryMatchingIsCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        val fan = product("1", "  Fans  ")

        val result = filterProductsByCategory(listOf(fan), "fans")

        assertEquals(listOf(fan), result)
    }

    @Test
    fun aProductWithNoCategoryNeverMatchesANamedFilter() {
        val uncategorised = product("1", category = null)

        val result = filterProductsByCategory(listOf(uncategorised), "Fans")

        assertEquals(emptyList<Product>(), result)
    }

    @Test
    fun countLabelIsThePlainTotalWhenNothingIsFiltered() {
        assertEquals("13 products", productListCountLabel(shown = 13, total = 13))
    }

    @Test
    fun countLabelUsesSingularForExactlyOneProduct() {
        assertEquals("1 product", productListCountLabel(shown = 1, total = 1))
    }

    @Test
    fun countLabelSplitsIntoXOfYOnceAFilterActuallyNarrowsTheList() {
        assertEquals("4 of 13 products", productListCountLabel(shown = 4, total = 13))
    }

    @Test
    fun countLabelStillSaysProductsPluralInTheXOfYFormEvenWhenTotalIsOne() {
        // total == 1 but shown == 0 (e.g. filtered to a category with no
        // match) is still the "of" form, not the singular total form.
        assertEquals("0 of 1 products", productListCountLabel(shown = 0, total = 1))
    }
}
