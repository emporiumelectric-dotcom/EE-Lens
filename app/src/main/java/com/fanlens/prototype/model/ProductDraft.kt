package com.fanlens.prototype.model

import com.fanlens.prototype.util.Money

/** One free-form specification row while it is being edited. */
data class SpecRow(val key: String = "", val value: String = "")

/**
 * What the owner has typed so far. Everything is held as text until save, so a
 * half-typed price is a validation message rather than a crash.
 */
data class ProductDraft(
    /** Null for a product that does not exist yet. */
    val id: String? = null,
    val brand: String = "",
    val name: String = "",
    val model: String = "",
    val category: String = "",
    val colour: String = "",
    val sizeSweep: String = "",
    /** Folded into specs["Wattage"] at save time; blank leaves any existing value alone. */
    val wattage: String = "",
    /** What the shop charges. */
    val priceText: String = "",
    /** List price it is discounted from; blank when the shop sells at MRP. */
    val mrpText: String = "",
    val description: String = "",
    val specs: List<SpecRow> = emptyList()
) {
    val isNew: Boolean get() = id == null

    /** Derived live from the two price fields, so it always agrees with them. */
    val discountPercent: Int?
        get() = Money.discountPercent(Money.parseToMinor(mrpText), Money.parseToMinor(priceText))

    /**
     * The dedicated Wattage field folds into the same "Wattage" key a spec row
     * could also set -- mirrors pc-catalogue-manager/app.js's applyWattageField.
     * Only ever set when the owner typed something here; leaving it blank
     * never erases a Wattage value entered as its own spec row instead (that
     * row's own removal is what clears it).
     */
    fun specsMap(): Map<String, String> {
        val fromRows = specs
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() }
        return if (wattage.isNotBlank()) fromRows + ("Wattage" to wattage.trim()) else fromRows
    }

    companion object {
        private fun priceField(minor: Long?): String = minor?.let {
            if (it % 100 == 0L) (it / 100).toString()
            else "${it / 100}.${(it % 100).toString().padStart(2, '0')}"
        }.orEmpty()

        fun from(product: Product): ProductDraft = ProductDraft(
            id = product.id,
            brand = product.brand,
            name = product.name,
            model = product.model,
            category = product.category.orEmpty(),
            colour = product.colour.orEmpty(),
            sizeSweep = product.sizeSweepMm?.toString().orEmpty(),
            wattage = product.specs["Wattage"].orEmpty(),
            priceText = priceField(product.priceMinor),
            mrpText = priceField(product.mrpMinor),
            description = product.description,
            specs = product.specs.map { SpecRow(it.key, it.value) }
        )
    }
}

data class DraftErrors(
    val brand: String? = null,
    val name: String? = null,
    val sizeSweep: String? = null,
    val price: String? = null,
    val mrp: String? = null
) {
    val isValid: Boolean
        get() = brand == null && name == null && sizeSweep == null && price == null && mrp == null
}

/**
 * Only brand and name are required — a shop should be able to save a product
 * from the shelf and fill in the rest later. Everything else is checked only if
 * something was typed.
 */
object DraftValidator {

    const val MAX_SWEEP_MM = 3000

    fun validate(draft: ProductDraft): DraftErrors = DraftErrors(
        brand = if (draft.brand.isBlank()) "Enter the brand" else null,
        name = if (draft.name.isBlank()) "Enter the product name" else null,
        sizeSweep = validateSweep(draft.sizeSweep),
        price = validatePrice(draft.priceText),
        mrp = validateMrp(draft)
    )

    /**
     * An MRP below the selling price is a typo, not a discount, and would show
     * the customer a negative saving.
     */
    private fun validateMrp(draft: ProductDraft): String? {
        val basic = validatePrice(draft.mrpText)
        if (basic != null) return basic
        val mrp = Money.parseToMinor(draft.mrpText) ?: return null
        val price = Money.parseToMinor(draft.priceText) ?: return null
        return if (mrp < price) "MRP is lower than the selling price" else null
    }

    private fun validateSweep(text: String): String? {
        if (text.isBlank()) return null
        val value = text.trim().toIntOrNull() ?: return "Size must be a number, in millimetres"
        return when {
            value <= 0 -> "Size must be more than zero"
            value > MAX_SWEEP_MM -> "Size looks too large — millimetres, not centimetres?"
            else -> null
        }
    }

    private fun validatePrice(text: String): String? {
        if (text.isBlank()) return null
        val minor = Money.parseToMinor(text) ?: return "Price must be a number, like 4250"
        return if (minor < 0) "Price cannot be negative" else null
    }
}
