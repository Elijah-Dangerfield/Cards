package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.domain.sampleStandingTable
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PreviewImageRendererTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    @Test fun rendersStandingAndOrdinaryPngsAtSocialPreviewSize() {
        val renderer = PreviewImageRenderer()
        val directory = File("build/share-evidence").apply { mkdirs() }
        for ((name, table) in listOf("standing-preview" to sampleStandingTable(), "ordinary-preview" to null)) {
            val png = renderer.render(table?.code ?: "ABCD23", table, now)
            val image = ImageIO.read(ByteArrayInputStream(png))
            assertEquals(1200, image.width)
            assertEquals(630, image.height)
            File(directory, "$name.png").writeBytes(png)
        }
    }
    @Test fun cachesByCodeAndUpdatedAtAndEvictsLeastRecentlyUsed() {
        val renderer = PreviewImageRenderer(capacity = 2)
        val first = renderer.render("AAAAAA", null, now)
        val second = renderer.render("BBBBBB", null, now)
        assertSame(first, renderer.render("AAAAAA", null, now))
        renderer.render("CCCCCC", null, now)
        assertEquals(2, renderer.cachedImageCount)
        assertSame(first, renderer.render("AAAAAA", null, now))
        assertNotSame(second, renderer.render("BBBBBB", null, now))
        assertNotSame(first, renderer.render("AAAAAA", sampleStandingTable("AAAAAA"), now))
    }
    @Test fun refreshesUneditedTablesSoDaylightSavingLabelsStayCurrent() {
        val renderer = PreviewImageRenderer()
        val table = sampleStandingTable()
        val first = renderer.render(table.code, table, now)
        assertNotSame(first, renderer.render(table.code, table, now + 5.minutes))
    }
    @Test fun defaultCacheIsBoundedAtTwoHundred() {
        val renderer = PreviewImageRenderer()
        repeat(201) { renderer.render(it.toString(), null, now) }
        assertEquals(200, renderer.cachedImageCount)
    }
}
