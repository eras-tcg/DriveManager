package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic

/**
 * What identifies a card by its name. The metadata, the image file and the card file can write the same card with or without its
 * printed accents and hyphens ("SAR 2" / "SAR-2", "Deriduragon" / "Deriduragón"): Drive's web export turns every space of a file
 * name into a hyphen, and a name typed from it loses the real ones. Every printing of a card is the same card except for its code
 * (owner's rule, 2026-11-11), so two names with the same key are one card. No dependencies: [com.drive.drive_manager.simulator.engine.cards.CardBundle]
 * uses it while [CardEffects] loads.
 */
object CardKeys {

    private val PLAIN: Map<Char, Char> = frozenMapOfEntries(
        'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
        'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i', 'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u', 'ñ' to 'n', 'ç' to 'c')

    /** Lower case, without accents, hyphens and spaces alike: "Roto-G0" and "roto g0" → "roto g0"; "Deriduragón" → "deriduragon". */
    @JvmStatic
    fun key(name: String?): String {
        if (name == null) return ""
        val out = StringBuilder()
        var gap = false
        for (c in name.lowercase()) {
            if (c == '-' || c == '‐' || c == '–' || c.isWhitespace()) {
                gap = true
                continue
            }
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            out.append(PLAIN[c] ?: c)
        }
        return out.toString()
    }
}
