package com.fanlens.prototype.model

import com.fanlens.prototype.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MRP, selling price and discount apply to every product the shop sells, not
 * just fans. Discount is always derived so it cannot disagree with the prices.
 */
class PricingTest {

    @Test
    fun theEmporionMagnaFiguresComeOutRight() {
        // MRP ₹30,000, selling ₹19,498 — the page states 35% off.
        assertEquals(35, Money.discountPercent(3_000_000, 1_949_800))
    }

    @Test
    fun discountIsWholePercentAndRounded() {
        assertEquals(33, Money.discountPercent(2_900_000, 1_949_800))
        assertEquals(50, Money.discountPercent(1_000_000, 500_000))
        assertEquals(1, Money.discountPercent(1_000_000, 990_000))
    }

    @Test
    fun noDiscountIsShownWhenThereIsNothingToShow() {
        assertNull("no MRP", Money.discountPercent(null, 100_000))
        assertNull("no price", Money.discountPercent(100_000, null))
        assertNull("sold at MRP", Money.discountPercent(100_000, 100_000))
        assertNull("MRP below price", Money.discountPercent(90_000, 100_000))
    }

    @Test
    fun aProductDerivesItsOwnDiscountAndStruckPrice() {
        val product = Product(
            id = "x", name = "Emperion Magna", model = "FG1050", description = "",
            priceMinor = 1_949_800, mrpMinor = 3_000_000
        )
        assertEquals("₹19,498", product.priceLabel)
        assertEquals("₹30,000", product.mrpLabel)
        assertEquals(35, product.discountPercent)
    }

    @Test
    fun sellingAtMrpShowsNeitherStrikeThroughNorDiscount() {
        val product = Product(
            id = "x", name = "Switch", model = "", description = "",
            priceMinor = 8_500, mrpMinor = 8_500
        )
        assertNull(product.mrpLabel)
        assertNull(product.discountPercent)
        assertEquals("₹85", product.priceLabel)
    }

    @Test
    fun pricingWorksForAnyCategoryNotJustFans() {
        val bulb = Product(
            id = "b", name = "9W LED Bulb", model = "", description = "", category = "LED bulb",
            priceMinor = 14_900, mrpMinor = 22_000
        )
        assertEquals(32, bulb.discountPercent)
        assertEquals("₹220", bulb.mrpLabel)
    }

    @Test
    fun draftDerivesDiscountFromWhatIsTyped() {
        val draft = ProductDraft(brand = "Atomberg", name = "Emperion Magna",
            mrpText = "30000", priceText = "19498")
        assertEquals(35, draft.discountPercent)
        assertTrue(DraftValidator.validate(draft).isValid)
    }

    @Test
    fun anMrpBelowTheSellingPriceIsRejected() {
        val draft = ProductDraft(brand = "A", name = "B", mrpText = "1000", priceText = "2000")
        val errors = DraftValidator.validate(draft)
        assertNotNull(errors.mrp)
        assertFalse(errors.isValid)
    }

    @Test
    fun anEmptyMrpIsPerfectlyFine() {
        val draft = ProductDraft(brand = "A", name = "B", priceText = "2000")
        assertTrue(DraftValidator.validate(draft).isValid)
        assertNull(draft.discountPercent)
    }

    @Test
    fun editingAnExistingProductRoundTripsBothPrices() {
        val product = Product(
            id = "x", name = "N", model = "M", description = "",
            priceMinor = 1_949_800, mrpMinor = 3_000_000
        )
        val draft = ProductDraft.from(product)
        assertEquals("19498", draft.priceText)
        assertEquals("30000", draft.mrpText)
    }
}
