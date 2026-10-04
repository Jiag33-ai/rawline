package app.rawline.feature.remove

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.rawline.core.ml.Healer
import app.rawline.core.model.BrushStroke
import app.rawline.core.render.EditorSession
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.RawSlider
import app.rawline.feature.editor.EditorState
import app.rawline.feature.editor.EditorTab
import app.rawline.feature.editor.PanelColumn
import app.rawline.feature.editor.PhotoMapper
import app.rawline.feature.editor.ToolGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Remove tab: AI remove (LaMa), heal and clone. One instance per open photo. */
class RemoveFeature(
    private val state: EditorState,
    private val session: EditorSession,
    private val healer: Healer,
    private val scope: CoroutineScope,
) {
    private var tool by mutableStateOf("remove")
    private var brushSize by mutableFloatStateOf(0.05f)
    private var feather by mutableFloatStateOf(0.4f)
    private var settingSource by mutableStateOf(false)
    private var source by mutableStateOf<Pair<Float, Float>?>(null)
    private val live = mutableStateListOf<Offset>()

    /** Leaving the Remove tool drops the unfinished stroke and the set-source mode. */
    fun onExit() { settingSource = false; live.clear() }
    val tab = EditorTab("remove", "Remove", onExit = ::onExit) { Content() }

    private fun pf(p: Offset): Offset {
        val g = state.recipe.geometry
        return Offset(g.cropX + p.x * g.cropW, g.cropY + p.y * g.cropH)
    }

    @Composable
    private fun Content() = PanelColumn {
        val busy by healer.busy.collectAsState()
        Column(Modifier.fillMaxWidth()) {
            Text(busy?.let { "$it..." } ?: "Paint over what you want gone.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("AI remove", tool == "remove", { tool = "remove"; settingSource = false })
                ChipButton("Heal", tool == "heal", { tool = "heal" })
                ChipButton("Clone", tool == "clone", { tool = "clone" })
                if (tool != "remove") ChipButton(if (source == null) "Set source" else if (settingSource) "Tap the photo..." else "Change source", settingSource, { settingSource = true })
            }
            if (tool != "remove" && source == null) Text("Heal and clone copy from a source spot. Tap Set source, then tap where to copy from.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            RawSlider("Size", brushSize * 100f, 1f..25f, 5f, decimals = 1, onChange = { brushSize = it / 100f }, onCommit = {})
            RawSlider("Feather", feather * 100f, 0f..100f, 40f, onChange = { feather = it / 100f }, onCommit = {})
            Text("${state.recipe.heals.size} repairs in this photo", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Undo last repair", false, { if (state.recipe.heals.isNotEmpty()) state.edit("Undo repair") { it.copy(heals = it.heals.dropLast(1)) } })
                ChipButton("Clear all", false, { state.edit("Clear repairs") { it.copy(heals = emptyList()) } })
            }
        }
    }

    fun gestures(tabId: String, mapper: PhotoMapper): ToolGestures? {
        if (tabId != "remove") return null
        if (healer.busy.value != null) return ToolGestures({ true }, {}, {})
        return ToolGestures(
            onDown = { pos ->
                if (settingSource) {
                    mapper.toImage(pos.x, pos.y)?.let { source = pf(it).let { f -> f.x to f.y } }
                    settingSource = false
                    return@ToolGestures true
                }
                live.clear(); live.add(pos)
                true
            },
            onMove = { pos -> if (live.isNotEmpty()) live.add(pos) },
            onUp = { cancelled ->
                val pts = live.toList(); live.clear()
                if (!cancelled && pts.isNotEmpty()) {
                    val frame = pts.mapNotNull { mapper.toImage(it.x, it.y) }.map { pf(it) }
                    if (frame.isEmpty()) return@ToolGestures
                    val stroke = BrushStroke(frame.flatMap { listOf(it.x, it.y) }, brushSize, feather, 1f, false)
                    val kind = tool
                    if (kind != "remove" && source == null) return@ToolGestures
                    scope.launch {
                        val op = runCatching { healer.add(kind, stroke, source) }.getOrNull()
                        if (op != null) state.edit(if (kind == "remove") "AI remove" else if (kind == "heal") "Heal" else "Clone") { it.copy(heals = it.heals + op) }
                    }
                }
            },
        )
    }

    @Composable
    fun BoxScope.Overlay(tabId: String, mapper: PhotoMapper) {
        if (tabId != "remove") return
        val pts = live.toList()
        val src = source
        Canvas(Modifier.fillMaxSize()) {
            if (pts.size > 1) {
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                // brush width in view px: size is a fraction of the displayed frame height
                val g = state.recipe.geometry
                val a = mapper.toView(0f, 0f); val b = mapper.toView(0f, 1f)
                val w = (b.y - a.y) / g.cropH * brushSize
                drawPath(path, Color(0x66FF4444), style = Stroke(width = w.coerceAtLeast(6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (src != null && tool != "remove") {
                val g = state.recipe.geometry
                val o = mapper.toView((src.first - g.cropX) / g.cropW, (src.second - g.cropY) / g.cropH)
                drawCircle(Color.White, 18f, o, style = Stroke(3f))
                drawLine(Color.White, Offset(o.x - 26f, o.y), Offset(o.x + 26f, o.y), 2f); drawLine(Color.White, Offset(o.x, o.y - 26f), Offset(o.x, o.y + 26f), 2f)
            }
        }
    }
}
