package com.ziribrowser.adblock

import java.io.BufferedReader

/** Which family of rules matched. Each can be switched on or off on its own, globally and per site. */
enum class ListKind { ADS, TRACKERS }

/** Resource types a filter rule can be limited to. */
object ResType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLE = 4
    const val XHR = 8
    const val SUBDOC = 16
    const val MEDIA = 32
    const val FONT = 64
    const val OTHER = 128
    /** The request type could not be worked out; matches rules that are not limited to images/styles/etc. */
    const val UNKNOWN = 0
    private const val UNKNOWN_MATCHES = SCRIPT or XHR or OTHER

    fun accepts(ruleTypes: Int, ruleNotTypes: Int, requestType: Int): Boolean {
        if (requestType == UNKNOWN) return ruleTypes == 0 || ruleTypes and UNKNOWN_MATCHES != 0
        if (ruleTypes != 0 && ruleTypes and requestType == 0) return false
        return ruleNotTypes and requestType == 0
    }
}

/** One parsed network filter, e.g. `||ads.example.com^$third-party,script`. */
class Rule(
    val pattern: String,            // lower-cased; may contain `*` and `^`
    val hostAnchor: Boolean,        // `||`
    val startAnchor: Boolean,       // `|` at the start
    val endAnchor: Boolean,         // `|` at the end
    val thirdParty: Int,            // 0 any, 1 third-party only, -1 first-party only
    val types: Int,
    val notTypes: Int,
    val domainsInclude: Array<String>?,
    val domainsExclude: Array<String>?,
) {
    val segments: Array<String> by lazy { pattern.split('*').toTypedArray() }
}

/** What is being asked about: a request URL, the page it came from, and what kind of resource it is. */
class Request(val url: String, val pageUrl: String, val type: Int) {
    val lower = url.lowercase()
    val host: String = hostOf(lower)
    val pageHost: String = hostOf(pageUrl.lowercase())
    val thirdParty: Boolean = pageHost.isNotEmpty() && registrable(host) != registrable(pageHost)
}

internal fun hostOf(url: String): String {
    val s = url.indexOf("://")
    if (s < 0) return ""
    var i = s + 3
    val at = url.indexOf('@', i).let { if (it in i until (url.indexOfAny(charArrayOf('/', '?', '#'), i).let { e -> if (e < 0) url.length else e })) it + 1 else -1 }
    if (at > 0) i = at
    var e = i
    while (e < url.length && url[e] != '/' && url[e] != ':' && url[e] != '?' && url[e] != '#') e++
    return url.substring(i, e)
}

private val twoLevelSecond = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

/** Approximate "site" of a host (example.co.uk, example.com): enough to tell first- from third-party. */
internal fun registrable(host: String): String {
    if (host.isEmpty() || host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
    val p = host.split('.')
    if (p.size <= 2) return host
    val take = if (p.last().length == 2 && p[p.size - 2] in twoLevelSecond) 3 else 2
    return p.takeLast(take).joinToString(".")
}

private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

/** Matches [seg] (which may contain `^`) at exactly [i]; returns the index after it, or -1. */
private fun matchHere(url: String, i0: Int, seg: String): Int {
    var i = i0
    for (c in seg) {
        if (c == '^') {
            if (i == url.length) continue
            if (!isSeparator(url[i])) return -1
            i++
        } else {
            if (i >= url.length || url[i] != c) return -1
            i++
        }
    }
    return i
}

private fun findSeg(url: String, from: Int, seg: String): Pair<Int, Int>? {
    if (seg.isEmpty()) return from to from
    val first = seg[0]
    var i = from
    while (i <= url.length) {
        if (first != '^') {
            i = url.indexOf(first, i)
            if (i < 0) return null
        }
        val e = matchHere(url, i, seg)
        if (e >= 0) return i to e
        i++
    }
    return null
}

private fun matchesFrom(rule: Rule, url: String, start: Int, anchoredStart: Boolean): Boolean {
    val segs = rule.segments
    var pos = start
    for ((k, seg) in segs.withIndex()) {
        val last = k == segs.lastIndex
        if (k == 0 && anchoredStart) {
            val e = matchHere(url, pos, seg)
            if (e < 0) return false
            pos = e
            if (last && rule.endAnchor && pos != url.length) return false
        } else if (last && rule.endAnchor) {
            var i = pos
            while (true) {
                val r = findSeg(url, i, seg) ?: return false
                if (r.second == url.length) break
                i = r.first + 1
            }
            pos = url.length
        } else {
            pos = (findSeg(url, pos, seg) ?: return false).second
        }
    }
    return true
}

private fun domainMatches(list: Array<String>, host: String) = list.any { host == it || host.endsWith(".$it") }

internal fun ruleMatches(rule: Rule, req: Request): Boolean {
    if (rule.thirdParty == 1 && !req.thirdParty) return false
    if (rule.thirdParty == -1 && req.thirdParty) return false
    if (!ResType.accepts(rule.types, rule.notTypes, req.type)) return false
    rule.domainsInclude?.let { if (!domainMatches(it, req.pageHost)) return false }
    rule.domainsExclude?.let { if (domainMatches(it, req.pageHost)) return false }
    val url = req.lower
    return when {
        rule.hostAnchor -> {
            val hs = url.indexOf("://").let { if (it < 0) return false else it + 3 }
            val he = hs + req.host.length
            var s = hs
            while (s <= he) {
                if (matchesFrom(rule, url, s, true)) return true
                val dot = url.indexOf('.', s)
                if (dot < 0 || dot >= he) break
                s = dot + 1
            }
            false
        }
        rule.startAnchor -> matchesFrom(rule, url, 0, true)
        else -> {
            // Unanchored: the first segment may start anywhere.
            val segs = rule.segments
            var from = 0
            while (from <= url.length) {
                val r = findSeg(url, from, segs[0]) ?: return false
                if (matchesFrom(rule, url, r.first, true)) return true
                from = r.first + 1
            }
            false
        }
    }
}

/** Rules grouped so that a lookup only tries the few that could match. */
private class Bucket {
    val byHost = HashMap<String, ArrayList<Rule>>()
    val byToken = HashMap<String, ArrayList<Rule>>()
    val generic = ArrayList<Rule>()
    var size = 0

    fun add(r: Rule) {
        size++
        hostKey(r)?.let { byHost.getOrPut(it) { ArrayList(1) }.add(r); return }
        tokenKey(r)?.let { byToken.getOrPut(it) { ArrayList(1) }.add(r); return }
        generic.add(r)
    }

    private fun hostKey(r: Rule): String? {
        if (!r.hostAnchor) return null
        val p = r.pattern
        var i = 0
        while (i < p.length && (p[i].isLetterOrDigit() || p[i] == '.' || p[i] == '-')) i++
        if (i < 3 || !p.substring(0, i).contains('.')) return null
        // "||ads.example" (no terminator) also matches ads.example.com; an exact-host key would never find it.
        if (i == p.length && !r.endAnchor) return null
        if (i < p.length && p[i] != '^' && p[i] != '/' && p[i] != ':' && p[i] != '?') return null
        return p.substring(0, i)
    }

    /** The longest run of letters/digits that is fully delimited inside the pattern, so it must appear as a whole token in a match. */
    private fun tokenKey(r: Rule): String? {
        val p = r.pattern
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (!(p[i].isLetterOrDigit() || p[i] == '%')) { i++; continue }
            var j = i
            while (j < p.length && (p[j].isLetterOrDigit() || p[j] == '%')) j++
            val leftOk = if (i == 0) (r.hostAnchor || r.startAnchor) else p[i - 1] != '*'
            val rightOk = if (j == p.length) r.endAnchor else p[j] != '*'
            if (leftOk && rightOk && j - i >= 3 && (best == null || j - i > best.length)) best = p.substring(i, j)
            i = j
        }
        return best
    }

    fun find(req: Request, tokens: List<String>): Rule? {
        var h = req.host
        while (true) {
            byHost[h]?.let { for (r in it) if (ruleMatches(r, req)) return r }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (t in tokens) byToken[t]?.let { for (r in it) if (ruleMatches(r, req)) return r }
        for (r in generic) if (ruleMatches(r, req)) return r
        return null
    }
}

private fun tokensOf(lower: String): List<String> {
    val out = ArrayList<String>(16)
    var i = 0
    while (i < lower.length) {
        if (!(lower[i].isLetterOrDigit() || lower[i] == '%')) { i++; continue }
        var j = i
        while (j < lower.length && (lower[j].isLetterOrDigit() || lower[j] == '%')) j++
        if (j - i >= 3) out += lower.substring(i, j)
        i = j
    }
    return out
}

/** Parsed rules of one list family (block rules, exceptions, and whole-site exceptions). */
class FilterSet {
    private val block = Bucket()
    private val allow = Bucket()
    private val siteAllow = Bucket()
    val blockCount get() = block.size
    val allowCount get() = allow.size + siteAllow.size

    fun add(line: String) {
        when (val p = FilterParser.parse(line) ?: return) {
            is Parsed.Block -> block.add(p.rule)
            is Parsed.Allow -> allow.add(p.rule)
            is Parsed.SiteAllow -> siteAllow.add(p.rule)
        }
    }

    fun addAll(reader: BufferedReader) { reader.forEachLine { add(it) } }

    /** True if this set blocks [req]: some block rule matches and no exception rule does. */
    fun blocks(req: Request): Boolean {
        if (block.size == 0) return false
        val tokens = tokensOf(req.lower)
        if (block.find(req, tokens) == null) return false
        if (allow.find(req, tokens) != null) return false
        // `@@||site^$document`: the whole page is exempt.
        if (req.pageUrl.isNotEmpty()) {
            val pageReq = Request(req.pageUrl, req.pageUrl, ResType.UNKNOWN)
            if (siteAllow.find(pageReq, tokensOf(pageReq.lower)) != null) return false
        }
        return true
    }
}

internal sealed interface Parsed {
    class Block(val rule: Rule) : Parsed
    class Allow(val rule: Rule) : Parsed
    class SiteAllow(val rule: Rule) : Parsed
}

/** Reads Adblock Plus / EasyList syntax. Rules it can't honour exactly are skipped, never half-applied. */
internal object FilterParser {
    private val typeNames = mapOf(
        "script" to ResType.SCRIPT, "image" to ResType.IMAGE, "stylesheet" to ResType.STYLE, "css" to ResType.STYLE,
        "xmlhttprequest" to ResType.XHR, "xhr" to ResType.XHR, "subdocument" to ResType.SUBDOC, "frame" to ResType.SUBDOC,
        "media" to ResType.MEDIA, "font" to ResType.FONT, "object" to ResType.OTHER, "other" to ResType.OTHER, "ping" to ResType.OTHER,
    )
    private val ignorable = setOf("important", "match-case", "all")

    fun parse(raw: String): Parsed? {
        var line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return null
        val hash = line.indexOf('#')
        if (hash >= 0 && (line.startsWith("#", hash) && hash + 1 < line.length && (line[hash + 1] == '#' || (hash + 2 < line.length && line[hash + 2] == '#' && line[hash + 1] in "@?$%")))) return null
        var exception = false
        if (line.startsWith("@@")) { exception = true; line = line.substring(2) }

        var types = 0
        var notTypes = 0
        var third = 0
        var include: ArrayList<String>? = null
        var exclude: ArrayList<String>? = null
        var document = false
        var pattern = line
        val dollar = line.lastIndexOf('$')
        if (dollar >= 0 && !(line.startsWith("/") && line.endsWith("/"))) {
            pattern = line.substring(0, dollar)
            for (opt in line.substring(dollar + 1).split(',')) {
                val o = opt.trim().lowercase()
                when {
                    o.isEmpty() || o in ignorable -> {}
                    o == "third-party" || o == "3p" -> third = 1
                    o == "~third-party" || o == "1p" || o == "first-party" -> third = -1
                    o == "document" || o == "doc" -> document = true
                    o.startsWith("domain=") -> o.substring(7).split('|').forEach { d ->
                        if (d.startsWith("~")) (exclude ?: ArrayList<String>().also { exclude = it }).add(d.substring(1))
                        else if (d.isNotEmpty()) (include ?: ArrayList<String>().also { include = it }).add(d)
                    }
                    o.startsWith("~") && typeNames.containsKey(o.substring(1)) -> notTypes = notTypes or typeNames.getValue(o.substring(1))
                    typeNames.containsKey(o) -> types = types or typeNames.getValue(o)
                    else -> return null // popup, csp, redirect, removeparam, … : not supported, so don't guess
                }
            }
        }
        if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 2) return null // regex rules
        if (pattern.isEmpty() || pattern == "*" || pattern == "|" || pattern == "||") return null

        var host = false
        var start = false
        var end = false
        if (pattern.startsWith("||")) { host = true; pattern = pattern.substring(2) }
        else if (pattern.startsWith("|")) { start = true; pattern = pattern.substring(1) }
        if (pattern.endsWith("|")) { end = true; pattern = pattern.dropLast(1) }
        pattern = pattern.lowercase().trimStart('*').let { if (host || start) pattern.lowercase() else it }
        if (pattern.isEmpty()) return null
        // A pattern that is only a few generic characters would match nearly everything.
        if (!host && !start && pattern.count { it.isLetterOrDigit() } < 3) return null

        val rule = Rule(pattern, host, start, end, third, types, notTypes, include?.toTypedArray(), exclude?.toTypedArray())
        return when {
            exception && document -> Parsed.SiteAllow(rule)
            document -> null
            exception -> Parsed.Allow(rule)
            else -> Parsed.Block(rule)
        }
    }
}
