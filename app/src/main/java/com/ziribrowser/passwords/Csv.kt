package com.ziribrowser.passwords

/** A login row in the CSV format Chrome, Edge, Firefox and others export: name,url,username,password. */
data class CsvLogin(val url: String, val username: String, val password: String)

object PasswordCsv {
    /** Splits CSV text into rows of fields, handling quotes, doubled quotes, commas and line breaks inside quotes. */
    fun rows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val t = text.removePrefix("\uFEFF")
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() { endField(); if (!(row.size == 1 && row[0].isEmpty())) rows.add(row); row = ArrayList() }
        while (i < t.length) {
            val c = t[i]
            when {
                inQuotes && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> endField()
                !inQuotes && c == '\n' -> endRow()
                !inQuotes && c == '\r' -> { if (i + 1 < t.length && t[i + 1] == '\n') i++; endRow() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Reads logins out of an exported file; columns are found by their header names, in any order. */
    fun parse(text: String): List<CsvLogin> {
        val all = rows(text)
        if (all.isEmpty()) return emptyList()
        val header = all[0].map { it.trim().lowercase() }
        val iUrl = header.indexOfFirst { it == "url" || it == "origin" || it == "website" || it == "login_uri" || it == "hostname" }
        val iUser = header.indexOfFirst { it == "username" || it == "user" || it == "login" || it == "login_username" || it == "email" }
        val iPass = header.indexOfFirst { it == "password" || it == "login_password" }
        if (iUrl < 0 || iUser < 0 || iPass < 0) return emptyList()
        return all.drop(1).mapNotNull { r ->
            val url = r.getOrNull(iUrl)?.trim().orEmpty()
            val pass = r.getOrNull(iPass).orEmpty()
            if (url.isEmpty() || pass.isEmpty()) null else CsvLogin(url, r.getOrNull(iUser).orEmpty(), pass)
        }
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.startsWith(" ") || s.endsWith(" ")) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun write(logins: List<CsvLogin>): String = buildString {
        append("name,url,username,password\r\n")
        for (l in logins) append(quote(PasswordPolicy.displayHost(l.url))).append(',').append(quote(l.url)).append(',')
            .append(quote(l.username)).append(',').append(quote(l.password)).append("\r\n")
    }
}
