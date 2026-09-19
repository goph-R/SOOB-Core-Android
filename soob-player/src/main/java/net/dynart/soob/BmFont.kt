package net.dynart.soob

/**
 * AngelCode BMFont (`.fnt` text format) parsing, measuring and drawing —
 * the port of SOOB-Core-Web's `src/host/text.ts`, which in turn mirrors the
 * desktop `uiText`.
 *
 * A text block is anchored at (x, y) per the ALIGN_* bitmask, glyphs laid out
 * by `xadvance` with per-glyph x/y offsets, sized by `scale` (a multiplier of
 * the font's native line height — 1 source px = 1 virtual px).
 */
class BmFont {

    class Glyph(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val xo: Int, val yo: Int, val xadv: Int,
    )

    var lineHeight = 16
    var base = 12
    var scaleW = 256
    var scaleH = 256
    var pageFile = ""
    val glyphs = HashMap<Int, Glyph>()

    companion object {
        private val TOKEN = Regex("""(\w+)=("[^"]*"|\S+)""")

        private fun kv(line: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (m in TOKEN.findAll(line)) {
                out[m.groupValues[1]] = m.groupValues[2].removeSurrounding("\"")
            }
            return out
        }

        fun parse(text: String): BmFont {
            val f = BmFont()
            for (raw in text.split('\n')) {
                val line = raw.trim()
                when {
                    line.startsWith("common ") -> {
                        val a = kv(line)
                        f.lineHeight = a["lineHeight"]?.toIntOrNull() ?: f.lineHeight
                        f.base = a["base"]?.toIntOrNull() ?: f.base
                        f.scaleW = a["scaleW"]?.toIntOrNull() ?: f.scaleW
                        f.scaleH = a["scaleH"]?.toIntOrNull() ?: f.scaleH
                    }
                    line.startsWith("page ") -> {
                        kv(line)["file"]?.let { f.pageFile = it }
                    }
                    line.startsWith("char ") -> {
                        val a = kv(line)
                        val id = a["id"]?.toIntOrNull() ?: continue
                        f.glyphs[id] = Glyph(
                            a["x"]?.toIntOrNull() ?: 0,
                            a["y"]?.toIntOrNull() ?: 0,
                            a["width"]?.toIntOrNull() ?: 0,
                            a["height"]?.toIntOrNull() ?: 0,
                            a["xoffset"]?.toIntOrNull() ?: 0,
                            a["yoffset"]?.toIntOrNull() ?: 0,
                            a["xadvance"]?.toIntOrNull() ?: 0,
                        )
                    }
                }
            }
            return f
        }
    }

    /** Rendered width in virtual px — the widest line of [text]. */
    fun measure(text: String, scale: Float): Float {
        var max = 0f
        var cur = 0f
        for (ch in text) {
            if (ch == '\n') {
                if (cur > max) max = cur
                cur = 0f
                continue
            }
            glyphs[ch.code]?.let { cur += it.xadv * scale }
        }
        return maxOf(max, cur)
    }

    /**
     * Draw [text] through the sprite batcher. `align` bits:
     * LEFT=1 CENTER=2 RIGHT=4 | TOP=8 MIDDLE=16 BOTTOM=32 (0 => TOP/LEFT).
     */
    fun draw(
        tex: Int, text: String, x: Float, y: Float, scale: Float, align: Int,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val lines = text.split('\n')
        val lh = lineHeight * scale
        val blockH = lh * lines.size

        val av = align and 56
        var top = y
        if (av == 16) top = y - blockH / 2f
        else if (av == 32) top = y - blockH

        val ah = align and 7
        val sw = scaleW.toFloat()
        val sh = scaleH.toFloat()

        for (li in lines.indices) {
            val line = lines[li]
            val w = measure(line, scale)
            var penX = x
            if (ah == 2) penX = x - w / 2f
            else if (ah == 4) penX = x - w
            val lineTop = top + li * lh

            for (ch in line) {
                val gl = glyphs[ch.code] ?: continue
                if (gl.w > 0 && gl.h > 0) {
                    Renderer.drawQuadTex(
                        tex,
                        penX + gl.xo * scale, lineTop + gl.yo * scale,
                        gl.w * scale, gl.h * scale,
                        gl.x / sw, gl.y / sh, (gl.x + gl.w) / sw, (gl.y + gl.h) / sh,
                        r, g, b, a, 0f,
                    )
                }
                penX += gl.xadv * scale
            }
        }
    }
}
