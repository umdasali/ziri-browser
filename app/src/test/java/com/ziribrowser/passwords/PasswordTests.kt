package com.ziribrowser.passwords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class PasswordPolicyTest {
    @Test fun originIgnoresPathAndDefaultPorts() {
        assertEquals("https://example.com", PasswordPolicy.originOf("https://Example.com/login?x=1"))
        assertEquals("https://example.com", PasswordPolicy.originOf("https://example.com:443/a"))
        assertEquals("https://example.com:8443", PasswordPolicy.originOf("https://example.com:8443/a"))
        assertEquals("http://localhost:3000", PasswordPolicy.originOf("http://localhost:3000/"))
        assertNull(PasswordPolicy.originOf("about:blank"))
        assertNull(PasswordPolicy.originOf("javascript:alert(1)"))
        assertNull(PasswordPolicy.originOf("not a url"))
    }

    @Test fun onlyHttpsAndLocalMachineAreEligible() {
        assertTrue(PasswordPolicy.eligible("https://bank.example"))
        assertTrue(PasswordPolicy.eligible("http://localhost:8080"))
        assertTrue(PasswordPolicy.eligible("http://127.0.0.1"))
        assertFalse(PasswordPolicy.eligible("http://bank.example"))
        assertFalse(PasswordPolicy.eligible("http://localhost.evil.com"))
        assertFalse(PasswordPolicy.eligible(null))
    }

    @Test fun matchingIsExactSoLookAlikeSitesGetNothing() {
        assertTrue(PasswordPolicy.matches("https://example.com", "https://example.com"))
        assertFalse(PasswordPolicy.matches("https://example.com", "https://login.example.com"))
        assertFalse(PasswordPolicy.matches("https://example.com", "https://example.com.evil.net"))
        assertFalse(PasswordPolicy.matches("https://example.com", "http://example.com"))
    }

    @Test fun decideSavesUpdatesOrIgnores() {
        val saved = listOf(SavedLogin(7, "ann", "pw1"))
        assertEquals(SaveDecision.Save, PasswordPolicy.decide(saved, "bob", "x"))
        assertEquals(SaveDecision.Ignore, PasswordPolicy.decide(saved, "ann", "pw1"))
        assertEquals(SaveDecision.Update(7), PasswordPolicy.decide(saved, "ann", "pw2"))
        assertEquals(SaveDecision.Ignore, PasswordPolicy.decide(saved, "ann", ""))
        assertEquals(SaveDecision.Ignore, PasswordPolicy.decide(emptyList(), "ann", "p".repeat(PasswordPolicy.MAX_FIELD + 1)))
    }

    @Test fun displayHostDropsWww() {
        assertEquals("example.com", PasswordPolicy.displayHost("https://www.example.com"))
        assertEquals("localhost:3000", PasswordPolicy.displayHost("http://localhost:3000"))
    }
}

class JsStringTest {
    @Test fun escapesQuotesBackslashesAndLineBreaks() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", JsString.quote("a\"b\\c\nd"))
    }

    @Test fun neverEmitsAScriptCloseOrRawLineSeparators() {
        val q = JsString.quote("</script><!--\u2028\u2029&")
        assertFalse(q.contains("</"))
        assertFalse(q.contains('<'))
        assertFalse(q.contains('>'))
        assertFalse(q.contains('\u2028'))
        assertFalse(q.contains('\u2029'))
    }

    @Test fun controlCharactersAreEscaped() {
        assertEquals("\"\\u0001\"", JsString.quote("\u0001"))
    }
}

class PasswordCsvTest {
    @Test fun parsesChromeStyleExport() {
        val csv = "name,url,username,password,note\r\nexample.com,https://example.com/login,ann,\"p,w\"\"1\",\r\n"
        assertEquals(listOf(CsvLogin("https://example.com/login", "ann", "p,w\"1")), PasswordCsv.parse(csv))
    }

    @Test fun keepsLineBreaksInsideQuotesAndSkipsRowsWithoutPassword() {
        val csv = "url,username,password\nhttps://a.com,u,\"line1\nline2\"\nhttps://b.com,u,\n\n"
        val r = PasswordCsv.parse(csv)
        assertEquals(1, r.size)
        assertEquals("line1\nline2", r[0].password)
    }

    @Test fun unknownHeaderGivesNothing() {
        assertTrue(PasswordCsv.parse("a,b,c\n1,2,3").isEmpty())
        assertTrue(PasswordCsv.parse("").isEmpty())
    }

    @Test fun writeThenParseRoundTrips() {
        val logins = listOf(CsvLogin("https://a.com", "me@x.com", "se\"cr,et"), CsvLogin("https://b.org", "", " pad "))
        assertEquals(listOf(logins[0]), PasswordCsv.parse(PasswordCsv.write(logins)).filter { it.url == "https://a.com" })
        assertEquals(" pad ", PasswordCsv.parse(PasswordCsv.write(logins)).first { it.url == "https://b.org" }.password)
    }
}

class SecretBoxTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = AesGcmBox { key }

    @Test fun roundTripKeepsUnicode() {
        val s = "pässwörd-密码-🔑"
        assertEquals(s, box.open(box.seal(s)))
    }

    @Test fun sameTextSealsDifferentlyEachTime() {
        assertNotEquals(box.seal("x"), box.seal("x"))
    }

    @Test fun tamperedOrForeignDataIsRejected() {
        val sealed = box.seal("secret")
        val flipped = sealed.dropLast(2) + (if (sealed.takeLast(2) == "AA") "BB" else "AA")
        assertNull(box.open(flipped))
        assertNull(box.open("not base64 !!"))
        assertNull(box.open(""))
        val other = AesGcmBox { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull(other.open(sealed))
    }
}
