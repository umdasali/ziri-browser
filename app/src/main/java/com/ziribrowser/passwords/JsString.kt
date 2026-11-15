package com.ziribrowser.passwords

/** Writes text as a JavaScript string literal that is safe to place inside a script, whatever the text contains. */
object JsString {
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                '<' -> out.append("\\u003c")   // never lets "</script>" appear
                '>' -> out.append("\\u003e")
                '&' -> out.append("\\u0026")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
