package com.example.emergencyringer.util

/**
 * PhoneNormalizer
 *
 * Android gives you phone numbers in many formats depending on the ROM:
 *   +91 98765 43210
 *   09876543210
 *   9876543210
 *   +919876543210
 *
 * We strip everything to digits only and compare the last N digits.
 * This is more reliable than E.164 normalization without a full
 * libphonenumber dependency.
 */
object PhoneNormalizer {

    private const val SIGNIFICANT_DIGITS = 10

    /**
     * Returns the last [SIGNIFICANT_DIGITS] digits of a phone number.
     * "+91 98765 43210" → "9876543210"
     */
    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > SIGNIFICANT_DIGITS) {
            digits.takeLast(SIGNIFICANT_DIGITS)
        } else {
            digits
        }
    }

    /**
     * Returns true if two phone numbers refer to the same subscriber.
     */
    fun matches(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return normalize(a) == normalize(b)
    }
}
