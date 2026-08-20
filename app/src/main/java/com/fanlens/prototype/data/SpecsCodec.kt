package com.fanlens.prototype.data

import org.json.JSONObject

/**
 * Flexible product specifications are stored as a flat JSON object of strings —
 * a shop adds "Warranty" or "Air delivery" without a schema change.
 */
object SpecsCodec {

    fun encode(specs: Map<String, String>): String {
        if (specs.isEmpty()) return "{}"
        val json = JSONObject()
        specs.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    /** Never throws: a malformed row yields no specs rather than losing the product. */
    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val parsed = JSONObject(json)
            buildMap {
                parsed.keys().forEach { key -> put(key, parsed.optString(key, "")) }
            }
        }.getOrDefault(emptyMap())
    }
}
