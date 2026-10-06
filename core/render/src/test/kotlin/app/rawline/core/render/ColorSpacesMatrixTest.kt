package app.rawline.core.render
import org.junit.Assert.assertEquals
import org.junit.Test
class ColorSpacesMatrixTest {
    // LibRaw 0.22.2 prophoto_rgb (src/tables/colordata.cpp), linear sRGB to ProPhoto, Bradford adapted
    private val libraw = doubleArrayOf(0.529317, 0.330092, 0.140588, 0.098368, 0.873465, 0.028169, 0.016879, 0.117663, 0.865457)
    @Test fun appMatrixEqualsLibrawsProphotoTable() {
        for (i in 0 until 9) assertEquals("element $i", libraw[i], ColorSpaces.SRGB_TO_WORKING[i].toDouble(), 5e-4)   // measured largest difference 1.4e-4
    }
    @Test fun everyRowSumsToOneSoNeutralStaysNeutral() {
        for (r in 0 until 3) assertEquals(1.0, (0 until 3).sumOf { ColorSpaces.SRGB_TO_WORKING[r * 3 + it].toDouble() }, 5e-4)
    }
}
