package com.morsedecoder.core.morse

/**
 * ITU Morse alphabet: letters, digits, punctuation, and prosigns.
 *
 * Prosigns are first-class patterns, not letter pairs (FR-6.4) — they are sent with no
 * inter-character gap and would decode as garbage otherwise.
 *
 * Some prosigns share a pattern with ITU punctuation (AR/+, BT/=, KN/'('), AS/&).
 * The reverse map resolves those to the punctuation character; genuinely distinct
 * prosigns (SK, HH) resolve to a bracketed token.
 */
object MorseAlphabet {

    val charPatterns: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.",
        '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.",
        '$' to "...-..-", '@' to ".--.-.",
    )

    /** Prosign token → element pattern. HH is the error prosign (FR-8.1). */
    val prosignPatterns: Map<String, String> = mapOf(
        "AR" to ".-.-.",   // end of message (same pattern as '+')
        "SK" to "...-.-",  // end of contact
        "BT" to "-...-",   // paragraph break (same pattern as '=')
        "KN" to "-.--.",   // go ahead, named station only (same pattern as '(')
        "AS" to ".-...",   // wait (same pattern as '&')
        "HH" to "........" // error / correction
    )

    /**
     * Reverse map: pattern → display token. Single characters render as themselves;
     * prosigns without a punctuation twin render as "<SK>", "<HH>".
     */
    val patternToToken: Map<String, String> = buildMap {
        for ((c, p) in charPatterns) put(p, c.toString())
        for ((name, p) in prosignPatterns) putIfAbsent(p, "<$name>")
    }

    fun patternFor(c: Char): String? = charPatterns[c.uppercaseChar()]

    fun tokenFor(pattern: String): String? = patternToToken[pattern]
}
