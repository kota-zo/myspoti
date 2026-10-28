package app.myspoti.spotify

/**
 * Spotify sends some descriptions (artist biographies, audiobooks) as small HTML snippets. This is
 * the pure-Kotlin counterpart of Android's `Html.fromHtml(...).toString()`, which this module cannot
 * use: line breaks keep their place, other tags go, and entities are decoded.
 */
fun stripHtml(html: String): String =
    decodeHtmlEntities(
        html.replace(Regex("<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), ""),
    ).replace(Regex("\n{3,}"), "\n\n").trim()

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,9});")

/** `&amp;`, `&eacute;`, `&#39;`, `&#x1F3B5;`... decoded in one pass (so `&amp;lt;` becomes `&lt;`). An unknown or invalid entity stays as written. */
fun decodeHtmlEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { m ->
        val body = m.groupValues[1]
        if (body[0] == '#') {
            val code = if (body[1] == 'x' || body[1] == 'X') body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
            if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else m.value
        } else NAMED[body]?.let { String(Character.toChars(it)) } ?: m.value
    }
}

// HTML 4's named entities: the Latin-1 block in code point order from U+00A0, then the rest.
private val LATIN1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro " +
    "para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute " +
    "Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN " +
    "szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute " +
    "ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ')
private val GREEK_UPPER = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho".split(' ')
private val GREEK_UPPER_2 = "Sigma Tau Upsilon Phi Chi Psi Omega".split(' ')
private val GREEK_LOWER = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigmaf sigma tau upsilon phi chi psi omega".split(' ')

private val NAMED: Map<String, Int> = buildMap {
    put("quot", 34); put("amp", 38); put("apos", 39); put("lt", 60); put("gt", 62)
    LATIN1.forEachIndexed { i, n -> put(n, 0xA0 + i) }
    GREEK_UPPER.forEachIndexed { i, n -> put(n, 913 + i) }
    GREEK_UPPER_2.forEachIndexed { i, n -> put(n, 931 + i) }
    GREEK_LOWER.forEachIndexed { i, n -> put(n, 945 + i) }
    putAll(mapOf(
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402, "circ" to 710, "tilde" to 732,
        "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205, "lrm" to 8206, "rlm" to 8207,
        "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218, "ldquo" to 8220, "rdquo" to 8221,
        "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230, "permil" to 8240, "prime" to 8242,
        "Prime" to 8243, "lsaquo" to 8249, "rsaquo" to 8250, "oline" to 8254, "frasl" to 8260, "euro" to 8364, "trade" to 8482,
        "larr" to 8592, "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596, "minus" to 8722, "infin" to 8734,
        "asymp" to 8776, "ne" to 8800, "equiv" to 8801, "le" to 8804, "ge" to 8805, "radic" to 8730, "sum" to 8721, "part" to 8706,
        "spades" to 9824, "clubs" to 9827, "hearts" to 9829, "diams" to 9830, "loz" to 9674, "thetasym" to 977, "upsih" to 978, "piv" to 982,
    ))
}
