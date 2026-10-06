package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeTargetTest {
    @Test fun smallPicturesAreLeftAlone() {
        assertNull(decodeTarget(6000, 4000, 8192))
        assertNull(decodeTarget(8192, 100, 8192))
    }

    @Test fun aHundredMegapixelPictureIsBroughtToTheCap() {
        val t = decodeTarget(12000, 8000, 8192)!!
        assertEquals(8192, t.first); assertEquals(5461, t.second)
        val portrait = decodeTarget(8000, 12000, 8192)!!
        assertEquals(5461, portrait.first); assertEquals(8192, portrait.second)
    }

    @Test fun neverReturnsAZeroSide() {
        val t = decodeTarget(100000, 3, 8192)!!
        assertEquals(8192, t.first); assertEquals(1, t.second)
    }
}
