package app.rawline.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTrimTest {
    private fun e(name: String, size: Long, t: Long) = CacheTrim.Entry(name, size, t)

    @Test fun underTheCapNothingIsDeleted() {
        assertTrue(CacheTrim.plan(listOf(e("1.jpg", 40, 1), e("2.jpg", 50, 2)), 100, 1000).isEmpty())
    }

    @Test fun oldestUseGoesFirstDownToTheTarget() {
        val files = (1..10).map { e("$it.jpg", 100, it.toLong()) }   // 1000 bytes, cap 800, target 720
        val gone = CacheTrim.plan(files, 800, 1000)
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), gone)      // 700 left, under 720
    }

    @Test fun recentlyUsedFilesSurviveEvenWithALowId() {
        val files = listOf(e("1.jpg", 100, 900), e("2.jpg", 100, 1), e("3.jpg", 100, 2))
        assertEquals(listOf("2.jpg", "3.jpg"), CacheTrim.plan(files, 150, 1000))
    }

    @Test fun staleTempFilesAreRemovedFreshOnesAreKept() {
        val now = 10_000_000L
        val files = listOf(e("1.123.tmp", 10, now - 20 * 60 * 1000), e("2.456.tmp", 10, now - 1000), e("3.jpg", 10, now))
        assertEquals(listOf("1.123.tmp"), CacheTrim.plan(files, 1000, now))
    }

    @Test fun tempFilesDoNotCountTowardsTheCap() {
        val files = listOf(e("1.9.tmp", 5000, 1000), e("2.jpg", 10, 1))
        assertTrue(CacheTrim.plan(files, 100, 1000).isEmpty())
    }
}
