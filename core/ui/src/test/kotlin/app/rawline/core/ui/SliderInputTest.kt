package app.rawline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderInputTest {
    @Test fun startsWithThePlainNumber() {
        assertEquals("12", SliderInput.initial(12.4f, 0))
        assertEquals("-0.35", SliderInput.initial(-0.35f, 2))
    }

    @Test fun unitsAreIgnored() {
        assertEquals(5500f, SliderInput.parse("5500 K")!!, 0f)
        assertEquals(1.5f, SliderInput.parse("+1.5 EV")!!, 0f)
    }

    @Test fun decimalCommaAndTypographicMinusWork() {
        assertEquals(1.25f, SliderInput.parse("1,25")!!, 0f)
        assertEquals(-3f, SliderInput.parse("−3")!!, 0f)
    }

    @Test fun nonNumbersAreRejectedNotSilentlyDropped() {
        assertNull(SliderInput.parse(""))
        assertNull(SliderInput.parse("abc"))
        assertNull(SliderInput.parse("-"))
        assertNull(SliderInput.parse("1-2"))
        assertNull(SliderInput.parse("1.2.3"))
    }

    @Test fun flipSignTogglesTheMinus() {
        assertEquals("-5", SliderInput.flipSign("5"))
        assertEquals("5", SliderInput.flipSign("-5"))
        assertEquals("-5", SliderInput.flipSign("+5"))
    }
}
