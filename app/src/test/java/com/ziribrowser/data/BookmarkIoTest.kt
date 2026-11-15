package com.ziribrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkIoTest {
    @Test fun `export then import round-trips titles urls and folders`() {
        val original = listOf(
            BookmarkEntity(title = "Example & Co", url = "https://example.com/?a=1&b=2"),
            BookmarkEntity(title = "Kotlin", url = "https://kotlinlang.org", folder = "Dev"),
            BookmarkEntity(title = "Android", url = "https://developer.android.com", folder = "Dev"),
        )
        val parsed = BookmarkIo.parseHtml(BookmarkIo.toHtml(original))
        assertEquals(original.map { Triple(it.title, it.url, it.folder) }.toSet(), parsed.map { Triple(it.title, it.url, it.folder) }.toSet())
    }

    @Test fun `import reads a browser export with nested folders`() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
              <DT><H3>Bar</H3>
              <DL><p>
                <DT><A HREF="https://a.com" ADD_DATE="1">A</A>
                <DT><H3>Inner</H3>
                <DL><p><DT><A HREF="https://b.com">B</A></DL><p>
                <DT><A HREF="https://c.com">C</A>
              </DL><p>
              <DT><A HREF="https://d.com">D</A>
            </DL><p>
        """.trimIndent()
        val byUrl = BookmarkIo.parseHtml(html).associate { it.url to it.folder }
        assertEquals("Bar", byUrl["https://a.com"])
        assertEquals("Inner", byUrl["https://b.com"])
        assertEquals("Bar", byUrl["https://c.com"])
        assertEquals("", byUrl["https://d.com"])
    }

    @Test fun `import ignores non-http links`() {
        val html = """<DL><DT><A HREF="javascript:alert(1)">x</A><DT><A HREF="file:///etc/passwd">y</A><DT><A HREF="https://ok.com">ok</A></DL>"""
        val urls = BookmarkIo.parseHtml(html).map { it.url }
        assertEquals(listOf("https://ok.com"), urls)
    }

    @Test fun `import of garbage returns nothing`() {
        assertTrue(BookmarkIo.parseHtml("not bookmarks at all").isEmpty())
        assertTrue(BookmarkIo.parseHtml("").isEmpty())
    }
}
