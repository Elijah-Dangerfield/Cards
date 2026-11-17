package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.domain.StandingTable
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PreviewImageRenderer(private val capacity: Int = 200) {
    private data class Key(val code: String, val updatedAt: Instant?)
    private data class CachedImage(val bytes: ByteArray, val renderedAt: Instant)
    private val cache = object : LinkedHashMap<Key, CachedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CachedImage>?): Boolean = size > capacity
    }
    private val titleFont = loadFont("DMSerifText-Regular.ttf", 80f)
    private val bodyFont = loadFont("Roboto-Medium.ttf", 40f)

    /** The same image for every code nobody is playing at, so a scan of random codes renders nothing new. */
    val notOpen: ByteArray by lazy { draw("This table isn't open", subtitle = null, code = null) }
    val closed: ByteArray by lazy { draw("This table has closed", subtitle = null, code = null) }

    @Synchronized
    fun render(code: String, table: StandingTable?, now: Instant): ByteArray {
        val key = Key(code, table?.updatedAt)
        cache[key]?.takeIf { now < it.renderedAt + 5.minutes }?.let { return it.bytes }
        return draw(table?.name ?: "Join my poker game", table?.let { scheduleText(it, now) }, code)
            .also { cache[key] = CachedImage(it, now) }
    }

    internal val cachedImageCount: Int get() = synchronized(this) { cache.size }

    private fun draw(title: String, subtitle: String?, code: String?): ByteArray {
        val image = BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // ColorResource.PokerFeltGreen -> Espresso950; the DS has one felt token.
            graphics.paint = GradientPaint(0f, 0f, Color(0x3F5B45), 0f, 630f, Color(0x121110))
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(0xFFFAF4) // ColorResource.WarmWhite
            graphics.font = titleFont
            graphics.drawString(ellipsize(title, graphics.fontMetrics, 1040), 80, 170)
            graphics.font = bodyFont
            subtitle?.let { graphics.drawString(it, 80, 250) }
            if (code != null) {
                graphics.color = Color(0x262320) // ColorResource.Espresso800
                graphics.fillRoundRect(80, 310, 530, 105, 32, 32)
                graphics.color = Color(0xE0BC52) // ColorResource.Gold500
                graphics.font = bodyFont.deriveFont(50f)
                graphics.drawString("Code $code", 110, 380)
            }
            graphics.color = Color(0xFFFAF4)
            graphics.font = titleFont.deriveFont(44f)
            graphics.drawString("Downcard", 80, 555)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun ellipsize(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return text.substring(0, end) + "…"
    }

    private fun loadFont(name: String, size: Float): Font =
        checkNotNull(javaClass.getResourceAsStream("/share/fonts/$name")).use {
            Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(size)
        }
}
