package net.dynart.soob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * BMFont parsing is pure Kotlin (no Android APIs until a glyph is drawn), so
 * it can be checked on the JVM — the one part of the host that is testable
 * without a device. Runs against Find5's real font when the sibling checkout
 * is there, plus a synthetic sample that is always available.
 */
class BmFontTest {

    private val sample = """
        info face="Test" size=22
        common lineHeight=26 base=20 scaleW=256 scaleH=128 pages=1
        page id=0 file="test_0.png"
        chars count=2
        char id=65 x=1 y=2 width=10 height=12 xoffset=1 yoffset=3 xadvance=11 page=0 chnl=15
        char id=66 x=20 y=2 width=8 height=12 xoffset=0 yoffset=3 xadvance=9 page=0 chnl=15
    """.trimIndent()

    @Test
    fun parsesCommonPageAndChars() {
        val f = BmFont.parse(sample)
        assertEquals(26, f.lineHeight)
        assertEquals(20, f.base)
        assertEquals(256, f.scaleW)
        assertEquals(128, f.scaleH)
        assertEquals("test_0.png", f.pageFile)
        assertEquals(2, f.glyphs.size)

        val a = f.glyphs['A'.code]!!
        assertEquals(1, a.x)
        assertEquals(2, a.y)
        assertEquals(10, a.w)
        assertEquals(12, a.h)
        assertEquals(1, a.xo)
        assertEquals(3, a.yo)
        assertEquals(11, a.xadv)
    }

    @Test
    fun measuresByAdvanceAndTakesTheWidestLine() {
        val f = BmFont.parse(sample)
        assertEquals(20f, f.measure("AB", 1f), 0.001f)   // 11 + 9
        assertEquals(40f, f.measure("AB", 2f), 0.001f)   // scale multiplies
        assertEquals(20f, f.measure("AB\nA", 1f), 0.001f) // widest line wins
        assertEquals(0f, f.measure("", 1f), 0.001f)
        assertEquals(0f, f.measure("?", 1f), 0.001f)     // unknown glyph: no advance
    }

    /**
     * Parse a real BMFont bake from whichever game this checkout builds, rather
     * than a hand-written fixture. Skipped when the game isn't checked out
     * beside the repo, or ships no .fnt.
     *
     * The path follows the `soobGame` Gradle property so the test tracks the
     * same setting as the build; the default keeps the historical ../../Find5.
     */
    @Test
    fun parsesRealGameFont() {
        val game = System.getProperty("soobGame") ?: "../../Find5"
        val fonts = File("$game/assets/fonts")
        assumeTrue("no game font directory at $fonts", fonts.isDirectory)

        val fnt = fonts.listFiles { f -> f.name.endsWith(".fnt") }?.firstOrNull()
        assumeTrue("game ships no .fnt in $fonts", fnt != null)

        val f = BmFont.parse(fnt!!.readText())
        assertTrue("expected a full ASCII range, got ${f.glyphs.size}", f.glyphs.size > 90)
        assertTrue(f.lineHeight > 0)
        assertTrue(f.pageFile.endsWith(".png"))
        assertNotNull("space must be present for word spacing", f.glyphs[' '.code])
        assertTrue(f.measure("SOOB", 1f) > 0f)
    }
}
