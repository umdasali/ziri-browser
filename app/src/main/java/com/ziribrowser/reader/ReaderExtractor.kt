package com.ziribrowser.reader

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/** Runs Mozilla's Readability (Apache 2.0, bundled in assets, no network) on the page to pull out the article. */
object ReaderExtractor {
    private const val MAX_HTML = 3_000_000

    @Volatile private var cached: String? = null

    private val WRAPPER = """
try {
  var __doc = document.cloneNode(true);
  var __r = new Readability(__doc, { charThreshold: 250 }).parse();
  if (!__r || !__r.content) return JSON.stringify({ ok: false });
  return JSON.stringify({ ok: true, title: __r.title || document.title || '', byline: __r.byline || '', siteName: __r.siteName || '',
    dir: __r.dir || document.dir || '', content: __r.content });
} catch (e) { return JSON.stringify({ ok: false }); }
"""

    /** The library plus a small wrapper, closed in a function so nothing is left behind on the page. */
    fun script(context: Context): String = cached ?: run {
        val lib = context.assets.open("reader/Readability.js").bufferedReader().use { it.readText() }
        "(function(){var module;\n$lib\n$WRAPPER})()".also { cached = it }
    }

    /** [raw] is what evaluateJavascript hands back: the script's result as a JSON string literal. */
    fun parse(raw: String?, baseUrl: String): Article? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(inner)
            if (!o.optBoolean("ok")) return null
            val html = o.optString("content")
            if (html.isBlank() || html.length > MAX_HTML) return null
            Article(o.optString("title"), o.optString("byline"), o.optString("siteName"), o.optString("dir"), html, baseUrl)
        }.getOrNull()
    }
}
