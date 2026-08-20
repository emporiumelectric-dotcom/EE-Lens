package com.fanlens.prototype.recognition

import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductSource

/**
 * The products shipped inside the APK.
 *
 * Since Phase 1 this is seed data only — it is copied into the editable local
 * catalogue on first launch and never consulted again at runtime. Recognition
 * and the Products list read from the database, so a seeded fan can be edited or
 * deleted like any other.
 */
data class CatalogEntry(
    val product: Product,
    val referenceAssets: List<String>
)

object BundledProductCatalog {

    val entries = listOf(
        CatalogEntry(
            product = seedProduct(
                id = "havells-stealth-air-pearl-white",
                brand = "Havells",
                name = "Havells Stealth Air",
                model = "1200 mm · Pearl White",
                colour = "Pearl White",
                description = "40W · 245 CMM air delivery · 280 RPM",
                specs = mapOf(
                    "Power" to "40 W",
                    "Air delivery" to "245 CMM",
                    "Speed" to "280 RPM"
                )
            ),
            referenceAssets = photos("stealth_air", 4)
        ),
        CatalogEntry(
            product = seedProduct(
                id = "atomberg-aris-contour-regent-grey",
                brand = "Atomberg",
                name = "Atomberg Aris Contour Smart",
                model = "1200 mm · Regent Grey",
                colour = "Regent Grey",
                description = "BLDC · IoT · Remote control · Ring LED",
                specs = mapOf(
                    "Motor" to "BLDC",
                    "Controls" to "Remote and app",
                    "Lighting" to "Ring LED"
                )
            ),
            referenceAssets = photos("aris_contour", 4)
        ),
        CatalogEntry(
            product = seedProduct(
                id = "havells-enticer-vineer",
                brand = "Havells",
                name = "Havells Enticer Vineer",
                model = "1200 mm · Vineer",
                colour = "Vineer",
                description = "Decorative · Dust-resistant · HPLV · High-speed",
                specs = mapOf(
                    "Finish" to "Dust resistant",
                    "Type" to "HPLV high speed"
                )
            ),
            referenceAssets = photos("enticer_vineer", 5)
        ),
        CatalogEntry(
            product = seedProduct(
                id = "havells-enticer-rosewood",
                brand = "Havells",
                name = "Havells Enticer Rosewood",
                model = "1200 mm · Rosewood",
                colour = "Rosewood",
                description = "Decorative · Dust-resistant · HPLV · High-speed",
                specs = mapOf(
                    "Finish" to "Dust resistant",
                    "Type" to "HPLV high speed"
                )
            ),
            referenceAssets = photos("enticer_rosewood", 5)
        ),
        CatalogEntry(
            product = seedProduct(
                id = "havells-ep-trendy-es-antique-brass",
                brand = "Havells",
                name = "Havells EP Trendy ES",
                model = "1200 mm · Antique Brass",
                colour = "Antique Brass",
                description = "Decorative ceiling fan",
                specs = mapOf("Type" to "Decorative")
            ),
            referenceAssets = photos("ep_trendy", 4)
        ),
        CatalogEntry(
            product = seedProduct(
                id = "atomberg-aris-gladius-pearl-white",
                brand = "Atomberg",
                name = "Atomberg Aris Gladius Smart",
                model = "1200 mm · Pearl White",
                colour = "Pearl White",
                description = "BLDC · IoT · Remote · ABS blades · 5-star rated",
                specs = mapOf(
                    "Motor" to "BLDC",
                    "Blades" to "ABS",
                    "Rating" to "5 star"
                )
            ),
            referenceAssets = photos("aris_gladius", 4)
        )
    )

    val productIds: List<String> get() = entries.map { it.product.id }

    fun entryById(id: String): CatalogEntry? = entries.firstOrNull { it.product.id == id }

    private fun seedProduct(
        id: String,
        brand: String,
        name: String,
        model: String,
        colour: String,
        description: String,
        specs: Map<String, String>
    ) = Product(
        id = id,
        slug = id,
        brand = brand,
        name = name,
        model = model,
        description = description,
        category = "Ceiling fan",
        colour = colour,
        sizeSweepMm = 1200,
        specs = specs,
        source = ProductSource.Bundled
    )

    private fun photos(folder: String, count: Int): List<String> =
        (1..count).map { "catalog/$folder/photo_$it.jpg" }
}
