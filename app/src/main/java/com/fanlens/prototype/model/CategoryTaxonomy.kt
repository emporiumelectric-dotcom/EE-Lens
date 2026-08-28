package com.fanlens.prototype.model

/**
 * The categories Electric Emporium stocks, and what each one usually needs.
 *
 * This is a straight port of pc-catalogue-manager/app.js's STANDARD_CATEGORIES,
 * SPEC_TEMPLATES and SIZE_OPTIONS -- kept identical on purpose, so a category
 * picked on the phone reads exactly the same as one picked on the PC tool,
 * never "Ceiling fan" on one side and "ceiling_fan" on the other. Both lists
 * are hardcoded here rather than fetched from anywhere (there is no shared
 * backend for category taxonomy yet); if the set of categories changes,
 * update both copies together.
 */
object CategoryTaxonomy {

    /** Keep this in the same order as pc-catalogue-manager/app.js's STANDARD_CATEGORIES. */
    val STANDARD_CATEGORIES: List<String> = listOf(
        "Fans",
        "Wall Fans",
        "Pedestal Fans",
        "Exhaust Fans",
        "Mixers",
        "Geysers",
        "AC",
        "LEDs",
        "Outdoor LEDs",
        "Fancy Lamps",
        "Wires/Cables",
        "Switches",
        "Distribution Box",
        "Stabilisers",
        "Induction Cooktops",
        "Kettles",
        "Immersion Rod Heaters",
        "Irons"
    )

    /**
     * Different categories need different fields -- a mixer has wattage and
     * jars, a bulb has wattage and colour temperature, a wall fan has two
     * sizes. These are only a starting point: every key and value stays
     * editable, and nothing here is required. Keep in sync with app.js's
     * SPEC_TEMPLATES.
     */
    private val SPEC_TEMPLATES: Map<String, List<String>> = mapOf(
        "fans" to listOf("Sweep", "Power", "Speed", "Air delivery", "Motor", "Star rating", "Warranty"),
        "wall fans" to listOf("Sweep", "Mounting", "Speed type", "Power", "Speeds", "Warranty"),
        "pedestal fans" to listOf("Sweep", "Height", "Power", "Speeds", "Warranty"),
        "exhaust fans" to listOf("Sweep", "Power", "Air delivery", "Mounting", "Warranty"),
        "mixers" to listOf("Wattage", "Number of jars", "Jar capacity", "Speeds", "Motor", "Warranty"),
        "geysers" to listOf("Capacity", "Wattage", "Type", "Pressure rating", "Star rating", "Warranty"),
        "ac" to listOf("Capacity", "Star rating", "Type", "Power", "Refrigerant", "Warranty"),
        "leds" to listOf("Wattage", "Colour temperature", "Base type", "Lumens", "Shape", "Warranty"),
        "outdoor leds" to listOf("Wattage", "Colour temperature", "IP rating", "Lumens", "Warranty"),
        "fancy lamps" to listOf("Type", "Holder", "Material", "Bulbs needed", "Warranty"),
        "wires/cables" to listOf("Cores", "Cross section", "Length", "Insulation", "Current rating", "Warranty"),
        "switches" to listOf("Rating", "Modules", "Type", "Finish", "Series", "Warranty"),
        "distribution box" to listOf("Ways", "Type", "Rating", "Mounting", "Warranty"),
        "stabilisers" to listOf("Capacity", "Input range", "Output", "Type", "Warranty"),
        "induction cooktops" to listOf("Wattage", "Presets", "Panel type", "Controls", "Warranty"),
        "kettles" to listOf("Capacity", "Wattage", "Material", "Warranty"),
        "immersion rod heaters" to listOf("Wattage", "Length", "Shock proof", "Warranty"),
        "irons" to listOf("Wattage", "Type", "Soleplate", "Steam", "Warranty")
    )

    /** Sizes each category actually comes in. Keep in sync with app.js's SIZE_OPTIONS. */
    private val SIZE_OPTIONS: Map<String, List<Int>> = mapOf(
        "fans" to listOf(600, 900, 1050, 1200, 1400),
        "wall fans" to listOf(300, 400, 450),
        "pedestal fans" to listOf(400, 450),
        "exhaust fans" to listOf(150, 200, 250, 300),
        "leds" to listOf(600, 1200),
        "immersion rod heaters" to listOf(1000, 1500, 2000)
    )

    /** The usual fields for [category], or null when nothing is known about it. Same lookup as app.js's templateFor(). */
    fun templateFor(category: String): List<String>? {
        val key = category.trim().lowercase()
        if (key.isEmpty()) return null
        SPEC_TEMPLATES[key]?.let { return it }
        val fallback = SPEC_TEMPLATES.keys.firstOrNull { key.contains(it) } ?: return null
        return SPEC_TEMPLATES.getValue(fallback)
    }

    /** The sizes [category] usually comes in, or empty when it has no standard sizes. */
    fun sizeOptionsFor(category: String): List<Int> {
        val key = category.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        SIZE_OPTIONS[key]?.let { return it }
        val fallback = SIZE_OPTIONS.keys.firstOrNull { key.contains(it) } ?: return emptyList()
        return SIZE_OPTIONS.getValue(fallback)
    }

    /** Whether [category] usually has a size/sweep worth its own field, e.g. fans. */
    fun hasSizeField(category: String): Boolean = sizeOptionsFor(category).isNotEmpty()

    /** Whether [category] usually has a wattage worth its own field, e.g. mixers, irons. */
    fun hasWattageField(category: String): Boolean =
        templateFor(category)?.any { it.equals("Wattage", ignoreCase = true) } == true
}
