package com.fanlens.prototype.recognition

/**
 * Catches the exact reported failure: a V-GUARD-branded water heater
 * confidently matched (69%) to "Havells Velora Prime" on shape alone --
 * MobileNetV3-Small (see EmbeddingGenerator's own doc comment) is a general
 * classification backbone, trained toward *invariance* to exactly the kind
 * of surface detail (embossed brand text, printed labels) that is the only
 * real difference between two similarly-shaped water heaters from
 * different manufacturers. Nothing in the embedding space reliably
 * separates them; MatchPolicy's thresholds were tuned against this
 * catalogue's own confusions, not against a shape genuinely absent from
 * it.
 *
 * This is a fundamentally different kind of correction from
 * ColorSignature.kt's colour tie-break, and deliberately does NOT follow
 * ColorSignature's "only consult a secondary signal on an already-close
 * embedding score" rule. That rule is right for colour, because colour
 * disambiguates between two REAL, KNOWN, in-catalogue candidates that are
 * already both plausible -- the embedding just can't tell which one -- so
 * it's safe to defer entirely to embedding score whenever that's already
 * decisive. This is the opposite kind of problem: the true object was
 * never a candidate at all, because it isn't in the catalogue, so there is
 * no near-tie for the runner-up to win -- the wrong catalogue product wins
 * outright, confidently, because it's the closest SHAPE among the
 * catalogue's own products, which is a different question from "is this
 * even the right product." Deferring only to close scores would never
 * fire for this exact reported case (69% is not a near-tie by
 * MatchPolicy's own MINIMUM_LEAD). So this is willing to veto even a
 * confident embedding match -- which is exactly why the two rules below
 * exist: it is a stronger, riskier kind of correction than a tie-break,
 * and needs correspondingly stronger evidence before it fires.
 *
 * Two rules, in order, keep this from being trigger-happy:
 *  1. Never reject for *missing* confirmation. Most frames won't have the
 *     matched product's own brand text legible at all -- wrong angle, too
 *     far, glare, a fabric/mesh grille with no flat nameplate surface. If
 *     [checkForConflict] required seeing the matched brand's own name to
 *     pass, it would reject the vast majority of genuinely correct
 *     matches, not just the rare wrong ones. So the ONLY thing that can
 *     downgrade a match is *positive* text for a DIFFERENT known brand --
 *     silence, or text that doesn't parse as any known brand at all
 *     (a model number, a wattage rating, a warranty sticker), is always
 *     [TextConflictVerdict.NoConflict].
 *  2. Require the conflicting text to be reasonably prominent in frame
 *     (see [MINIMUM_RELATIVE_AREA]), not just present anywhere ML Kit
 *     found a text block. A shop shelf is rarely one isolated product --
 *     a neighbouring item's own label drifting into the edge of the crop
 *     is a real, concrete way this could misfire on a genuinely correct
 *     match, and prominence (roughly: is this text large/close, the way
 *     text on the actual product being aimed at would be, rather than a
 *     smaller item in the background) is the only signal available to
 *     guard against it -- ML Kit's on-device Text Recognition API does
 *     not expose a numeric per-word confidence score the way, say,
 *     Tesseract does (see this repo's OCR investigation notes), so
 *     prominence via boundingBox size is what stands in for "how much to
 *     trust this read" here.
 */
object BrandTextConflict {

    /**
     * Brand names this app's shop segment can realistically run into on a
     * shelf, independent of what's actually in any one shop's own
     * catalogue -- the whole point is catching a brand that ISN'T in the
     * catalogue (V-GUARD was never a product here). Deliberately a plain,
     * growable list rather than anything derived from the local database;
     * add to it as real false negatives turn up. Multi-word entries (e.g.
     * "BLUE STAR") are matched with internal whitespace collapsed, the
     * same as every other entry -- see [compact].
     */
    val KNOWN_BRANDS: List<String> = listOf(
        "HAVELLS", "CROMPTON", "ATOMBERG", "ORIENT", "USHA", "BAJAJ",
        "V-GUARD", "POLYCAB", "ANCHOR", "SYSKA", "ORPAT", "KHAITAN",
        "SYMPHONY", "VOLTAS", "BLUE STAR", "RACOLD", "AO SMITH",
        "LUMINOUS", "FINOLEX", "WIPRO", "PHILIPS", "PANASONIC",
        "PRESTIGE", "BUTTERFLY", "PIGEON", "MORPHY RICHARDS", "KENT",
        "EUREKA FORBES", "HINDWARE", "LG", "SAMSUNG", "WHIRLPOOL"
    )

    /**
     * A detected text block's area as a fraction of the whole frame it was
     * found in (width_px * height_px of the block, divided by width_px *
     * height_px of the frame) -- deliberately unitless/frame-independent
     * so this doesn't need to know the camera's actual resolution.
     */
    private const val MINIMUM_RELATIVE_AREA = 0.006f

    /** One block of text ML Kit found, with how much of the frame it covers. */
    data class DetectedText(val text: String, val relativeArea: Float)

    sealed interface TextConflictVerdict {
        /** The matched brand's own text was found (or nothing useful was). Never blocks a match. */
        data object NoConflict : TextConflictVerdict

        /** A DIFFERENT known brand was read, prominently enough to trust. */
        data class Conflict(val foundBrand: String) : TextConflictVerdict
    }

    /**
     * The one entry point: does anything ML Kit found in [detections]
     * positively contradict [matchedBrand]? See the class doc for why this
     * is asymmetric (missing confirmation is never a conflict; a
     * prominent different brand always is).
     */
    fun checkForConflict(matchedBrand: String, detections: List<DetectedText>): TextConflictVerdict {
        val matchedCompact = compact(matchedBrand)
        if (matchedCompact.isEmpty()) return TextConflictVerdict.NoConflict

        // Every prominent block is joined into one blob before comparing, not
        // checked block-by-block -- deliberately, so a wordmark ML Kit split
        // across two blocks around a logo mark ("V" / "GUARD") still reads as
        // one brand name. The trade-off: two unrelated fragments that happen
        // to land next to each other in frame (rare, but real on a crowded
        // shelf) could in principle concatenate into a brand name neither one
        // said alone. Left as-is for a first version -- worth watching for in
        // real-device testing before this ships, not something fixable from
        // this text alone without real false-positive examples to tune against.
        val prominentText = detections.filter { it.relativeArea >= MINIMUM_RELATIVE_AREA }.map { it.text }
        val combinedCompact = compact(prominentText.joinToString(" "))
        val combinedTokens = prominentText.flatMap(::tokensOf)

        // The matched brand's own name, seen anywhere prominent, always wins --
        // checked first and unconditionally, even if some other, smaller brand
        // name also happens to appear (a "compatible with" sticker, a nearby
        // accessory's own tiny label, etc). Both forms of [matchedBrand] are
        // tried, the same as for KNOWN_BRANDS below, for the same reason.
        if (matches(matchedBrand, matchedCompact, combinedCompact, combinedTokens)) {
            return TextConflictVerdict.NoConflict
        }

        for (brand in KNOWN_BRANDS) {
            val brandCompact = compact(brand)
            if (brandCompact == matchedCompact || brandCompact.isEmpty()) continue // never flag the matched brand as its own conflict
            if (matches(brand, brandCompact, combinedCompact, combinedTokens)) {
                return TextConflictVerdict.Conflict(brand)
            }
        }

        return TextConflictVerdict.NoConflict
    }

    /**
     * Whether [brand] was read in the frame -- long names via a plain
     * substring check on the fully punctuation/space-free form (so
     * "V-GUARD", "V GUARD", and "VGUARD" all compare equal regardless of
     * which separator style the OCR happened to produce); short names
     * (below [MINIMUM_BRAND_LENGTH_FOR_SUBSTRING], today just "LG") via a
     * whole-token match instead, since a bare substring check on a 2-3
     * letter name is too easy to hit by accident inside an unrelated,
     * longer word ("LG" inside "ELGIN").
     */
    private fun matches(brand: String, brandCompact: String, combinedCompact: String, combinedTokens: List<String>): Boolean =
        if (brandCompact.length >= MINIMUM_BRAND_LENGTH_FOR_SUBSTRING) {
            combinedCompact.contains(brandCompact)
        } else {
            val brandTokens = tokensOf(brand)
            brandTokens.isNotEmpty() && containsRun(combinedTokens, brandTokens)
        }

    /** Below this length, a substring match is too easy to hit by accident -- see [matches]. */
    private const val MINIMUM_BRAND_LENGTH_FOR_SUBSTRING = 4

    /** Upper-case letters and digits only -- no spaces, no punctuation. "V-Guard", "V Guard" and "VGUARD" all become "VGUARD". */
    private fun compact(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /** Upper-case whole words, splitting on any run of non-alphanumeric characters -- hyphens and spaces are both treated as word breaks. */
    private fun tokensOf(s: String): List<String> = s.uppercase().split(NON_ALNUM).filter { it.isNotEmpty() }

    private val NON_ALNUM = Regex("[^A-Z0-9]+")

    /** Whether [needle] occurs as a contiguous run inside [haystack] (both already tokenized). */
    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start -> haystack.subList(start, start + needle.size) == needle }
    }
}
