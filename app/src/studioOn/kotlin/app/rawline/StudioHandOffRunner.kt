package app.rawline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.rawline.core.ml.Denoiser
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.ColorSpaceOut
import app.rawline.core.render.ExportFormat
import app.rawline.core.render.ExportSettings
import app.rawline.core.render.Exporter
import app.rawline.core.render.MetadataMode
import app.rawline.core.render.SharpenFor
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.HandOffFiles
import app.rawline.core.studio.model.HandOffPlan
import app.rawline.core.studio.model.HandOffRequest
import app.rawline.core.studio.model.NewProject
import app.rawline.core.studio.model.ProjectCatalog
import app.rawline.core.studio.model.RawPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Open in Studio, the app side of decision D9 (docs/STUDIO_SPEC.md 3.7): renders the photo with the edit it has now through the same export pipeline Develop uses, at most 12 megapixels
 * (the S2 canvas limit until tiled layers land), and builds a [HandOffRequest]. Develop's catalogue, recipe and masks are read, never written. Runs off the main thread.
 *
 * Not done yet: rendering a preview first and the full size after (this renders once), and "Preview only" for a RAW that cannot be developed (that flag arrives with the DNG probe, W15).
 */
internal object StudioHandOffRunner {
    private const val CAP_PIXELS = 12_000_000.0

    suspend fun prepare(context: Context, graph: Graph, photo: Photo, recipe: EditRecipe): HandOffRequest? = withContext(Dispatchers.IO) {
        val exporter = Exporter(context, graph.maskStore, graph.patchStore) { h, a, l, p -> Denoiser(context, graph.modelStore).let { d -> try { d.run(h, a, l, p) } finally { d.release() } } }
        val settings = ExportSettings(format = ExportFormat.PNG, longEdge = longEdgeFor(photo), sharpenFor = SharpenFor.NONE, colorSpace = ColorSpaceOut.SRGB, metadata = MetadataMode.NONE)
        val result = exporter.render(photo, recipe, settings, null, onProgress = {}) ?: return@withContext null
        var bmp: Bitmap = result.bitmap ?: return@withContext null
        val (fw, fh) = NewProject.fit(bmp.width, bmp.height)
        if (fw != bmp.width || fh != bmp.height) bmp = Bitmap.createScaledBitmap(bmp, fw, fh, true)
        val rgba = ByteArray(fw * fh * 4)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(rgba))   // the render is opaque, so premultiplied and straight bytes are the same
        val plan = HandOffPlan.make(ProjectCatalog.newId(System.currentTimeMillis()), photo.name, fw, fh, previewOnly = false, nowMs = System.currentTimeMillis())
        val recipeJson = recipe.toJson()
        val uri = Uri.parse(photo.uri)
        val ext = photo.name.substringAfterLast('.', "")
        HandOffRequest(plan.document, mapOf(HandOffPlan.LAYER_ID to RawPixels(fw, fh, rgba)), afterFirstSave = { dir ->
            // source first (the big one), then the small recipe copy; each is atomic, and both stay inside the project folder
            context.contentResolver.openInputStream(uri)?.let { HandOffFiles.copySource(it, dir, ext) }
            HandOffFiles.writeRecipe(dir, recipeJson)
        })
    }

    /** The long edge to render at: full size when the photo is within 12 MP, else the size that has 12 MP in the photo's shape (0 means full size to the exporter). */
    private fun longEdgeFor(photo: Photo): Int {
        val w = photo.width; val h = photo.height
        if (w <= 0 || h <= 0 || w.toDouble() * h <= CAP_PIXELS) return 0
        return max(1, (max(w, h) * sqrt(CAP_PIXELS / (w.toDouble() * h))).toInt())
    }
}
