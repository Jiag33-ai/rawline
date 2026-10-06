package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.InputEvent
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.Phase as InPhase
import app.rawline.core.studio.model.PointerKind
import app.rawline.core.studio.model.RawPixels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class StudioProjectsTest {
    @Test fun sizeLimitsMatchTheDocumentCaps() {
        assertNull(StudioProjects.sizeProblem(4000, 3000))
        assertNull(StudioProjects.sizeProblem(1, 1))
        assertTrue(StudioProjects.sizeProblem(4001, 3000)!!.contains("12 megapixels"))
        assertTrue(StudioProjects.sizeProblem(9000, 100)!!.contains("8192"))
        assertTrue(StudioProjects.sizeProblem(null, 100)!!.contains("Enter"))
        assertTrue(StudioProjects.sizeProblem(0, 100)!!.contains("at least 1"))
    }

    @Test fun photoScaleFitsTheCapAndNeverUpscales() {
        assertEquals(1.0, PhotoImport.fitScale(1000, 800, 8192, 8192), 0.0)
        val s = PhotoImport.fitScale(6000, 4000, 8192, 8192)               // 24 MP down to 12 MP
        assertEquals(12_000_000.0, 6000 * s * 4000 * s, 1000.0)
        assertEquals(0.5, PhotoImport.fitScale(2000, 1000, 1000, 1000), 0.0)
    }

    @Test fun aRealProjectSurvivesOnJavaFsAndTheLatestOneIsFound() {
        val dir = Files.createTempDirectory("studio-test").toFile()
        try {
            val projects = StudioProjects(dir, "test")
            val p = projects.newBlank(40, 30, "Real")
            val direct = Executor { it.run() }
            val gl = FakeGl()
            val env = StudioEnv(direct, direct, { _, _ -> }, clock = { 5_000_000L })
            val s = StudioSession(projects.fs(), projects.rootOf(p.document.id), gl, env, p.document, p.pixels, onDisk = false)
            s.start()
            s.setBrush(Brush(diameter = 6.0, hardness = 1.0, pressureSize = false)); s.setColour(Rgb(1f, 0f, 0f))
            var t = 0L
            for ((ph, x) in listOf(InPhase.DOWN to 5f, InPhase.MOVE to 25f, InPhase.UP to 25f)) s.onInput(InputEvent(ph, 0, x, 10f, 1f, PointerKind.FINGER, t++))
            s.flush()
            assertEquals(SaveState.SAVED, s.state.value.save)
            assertEquals(p.document.id, projects.latestId())
            val opened = projects.open(p.document.id)
            assertEquals("Real", opened.document.name)
            val store = app.rawline.core.studio.model.ProjectStore(projects.fs(), projects.rootOf(p.document.id))
            val onDisk = store.load(opened.document.layers[0] as Layer.Pixel)!!
            assertArrayEquals(gl.gpu.tex[0]!!.rgba, onDisk.rgba)
            assertTrue(File(dir, "studio/${p.document.id}/project.json").isFile)
            assertNotNull(StudioProjects(dir).latestId())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPhotoProjectTakesItsCanvasFromThePicture() {
        val projects = StudioProjects(Files.createTempDirectory("studio-test2").toFile())
        val photo = RawPixels(30, 20, ByteArray(30 * 20 * 4) { 9 })
        val p = projects.newFromPhoto(photo, "holiday")
        assertEquals(30, p.document.width); assertEquals(20, p.document.height); assertEquals("holiday", p.document.name)
        assertEquals(photo, p.pixels["bg"])
    }
}
