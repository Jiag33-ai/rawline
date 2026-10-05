package app.rawline.core.ml

import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun everyLinkIsHttps() = Models.ALL.forEach { assertTrue(it.id, it.url.startsWith("https://")) }

    @Test fun pinnedChecksumsAreWellFormed() = Models.ALL.forEach { p ->
        p.sha256?.let { assertTrue("${p.id} checksum", Regex("[0-9a-f]{64}").matches(it)) }
    }

    @Test fun versionedLinksArePinned() = Models.ALL.filter { !it.url.contains("/latest/") }.forEach { assertTrue("${it.id} must have a checksum", it.sha256 != null) }
}
