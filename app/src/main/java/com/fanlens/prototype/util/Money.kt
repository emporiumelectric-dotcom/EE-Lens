package com.fanlens.prototype.util

import kotlin.math.abs

/**
 * Prices are stored as whole minor units — paise for rupees — so that arithmetic
 * never picks up the rounding drift that floating-point money is prone to.
 */
object Money {

    private val symbols = mapOf(
        "INR" to "₹",
        "USD" to "$",
        "EUR" to "€",
        "GBP" to "£"
    )

    /** Returns null when there is no price to show, so callers can skip the row entirely. */
    fun format(minor: Long?, currency: String = "INR"): String? {
        if (minor == null) return null
        val symbol = symbols[currency.uppercase()] ?: (currency.uppercase() + " ")
        val negative = minor < 0
        val absolute = abs(minor)
        val whole = absolute / 100
        val fraction = (absolute % 100).toInt()

        val grouped = if (currency.equals("INR", ignoreCase = true)) {
            groupIndian(whole)
        } else {
            groupWestern(whole)
        }

        val body = if (fraction == 0) grouped else "$grouped.${fraction.toString().padStart(2, '0')}"
        return if (negative) "-$symbol$body" else "$symbol$body"
    }

    /**
     * Indian digit grouping: the last three digits, then pairs.
     * 1234567 becomes 12,34,567 rather than 1,234,567.
     */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits

        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val pairs = StringBuilder()
        var index = head.length
        while (index > 2) {
            pairs.insert(0, "," + head.substring(index - 2, index))
            index -= 2
        }
        if (index > 0) pairs.insert(0, head.substring(0, index))
        return "$pairs,$tail"
    }

    fun groupWestern(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        var count = 0
        for (i in digits.lastIndex downTo 0) {
            out.insert(0, digits[i])
            count++
            if (count % 3 == 0 && i > 0) out.insert(0, ',')
        }
        return out.toString()
    }

    /**
     * Percentage off, rounded to whole percent. Null unless both prices are
     * present and the MRP is genuinely the higher of the two — a "0% off" or a
     * negative discount is a data-entry mistake, not something to display.
     */
    fun discountPercent(mrpMinor: Long?, priceMinor: Long?): Int? {
        if (mrpMinor == null || priceMinor == null) return null
        if (mrpMinor <= 0 || priceMinor < 0 || mrpMinor <= priceMinor) return null
        return Math.round((mrpMinor - priceMinor) * 100.0 / mrpMinor).toInt()
    }

    /** Parses owner-typed text such as "4250", "4,250" or "4250.50" into paise. */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim().replace(",", "").removePrefix("₹").trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (parts.size == 1) return whole * 100
        val fractionText = parts[1].padEnd(2, '0')
        if (fractionText.length > 2) return null
        val fraction = fractionText.toLongOrNull() ?: return null
        return whole * 100 + fraction
    }
}
