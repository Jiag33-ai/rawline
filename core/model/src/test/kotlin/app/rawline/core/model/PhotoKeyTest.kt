package app.rawline.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoKeyTest {
    private fun photo(name: String = "A.RW2", size: Long = 10, modified: Long = 5) =
        Photo(1, "f", "u", name, size, modified, Kind.RAW, true)

    @Test fun keyIsNameSizeAndModifiedInThatFormat() {
        assertEquals("A.RW2|10|5", photo().key)
        assertEquals(Photo.keyOf("A.RW2", 10, 5), photo().key)
    }

    @Test fun anyOfTheThreePartsChangesTheKey() {
        val base = photo().key
        assertNotEquals(base, photo(name = "B.RW2").key)
        assertNotEquals(base, photo(size = 11).key)
        assertNotEquals(base, photo(modified = 6).key)
    }

    @Test fun idAndFolderDoNotAffectTheKey() {
        assertEquals(photo().key, photo().copy(id = 99, folderUri = "other", uri = "elsewhere").key)
    }

    @Test fun fileTypesAreRecognisedIgnoringCase() {
        assertEquals(Kind.RAW, FileTypes.kindOf("P1055415.RW2"))
        assertEquals(Kind.RAW, FileTypes.kindOf("x.dng"))
        assertEquals(Kind.IMAGE, FileTypes.kindOf("x.JPG"))
        assertEquals(Kind.IMAGE, FileTypes.kindOf("x.heic"))
    }

    @Test fun otherFilesAreNotPhotos() {
        assertNull(FileTypes.kindOf("notes.txt"))
        assertNull(FileTypes.kindOf("rw2"))        // no dot: a name, not an extension
        assertNull(FileTypes.kindOf("clip.mp4"))
        assertNull(FileTypes.kindOf("P1.RW2.xmp"))
    }
}
