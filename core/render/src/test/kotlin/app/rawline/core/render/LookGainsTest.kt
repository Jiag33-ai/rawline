package app.rawline.core.render

import app.rawline.core.model.Look
import org.junit.Assert.assertEquals
import org.junit.Test

class LookGainsTest {
    private val gains = floatArrayOf(1.25f, 2.0117f)   // [look 1 white point factor, look 2 white balance gain]

    @Test fun lookOneUsesTheOldWhitePointFactorAndLookTwoTheWhiteBalanceGain() {
        assertEquals(1.25f, LookGains.srcGain(Look.V1, gains), 0f)
        assertEquals(2.0117f, LookGains.srcGain(Look.V2, gains), 0f)
    }

    @Test fun aLookFromTheFutureUsesTheNewestGainWeKnow() {
        assertEquals(2.0117f, LookGains.srcGain(9, gains), 0f)
        assertEquals(1.25f, LookGains.srcGain(0, gains), 0f)
    }

    @Test fun aFinishedPictureAlwaysHasGainOne() {
        assertEquals(1f, LookGains.srcGain(Look.V1, gains, finishedPicture = true), 0f)
        assertEquals(1f, LookGains.srcGain(Look.V2, gains, finishedPicture = true), 0f)
    }

    @Test fun missingOrNonsenseGainsDoNotBreakTheDrawing() {
        assertEquals(1f, LookGains.srcGain(Look.V2, floatArrayOf()), 0f)
        assertEquals(1f, LookGains.srcGain(Look.V2, floatArrayOf(1f, 0f)), 0f)
        assertEquals(1f, LookGains.srcGain(Look.V1, floatArrayOf(Float.NaN, 2f)), 0f)
    }
}
