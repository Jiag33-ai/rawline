# W20 Studio S1c: home, mode switch, studio.db, flatten export, flag and CI matrix

Written 6 Oct 2026 against main c6f8a39 (S1a and S1b host parts are merged). Starts after W19 (S1b) has its canvas screen. Parent: `docs/STUDIO_SPEC.md` section 7, milestone S1, slice 3 of 3 (BK-398); this finishes S1.

What was verified here, and what was not. Verified: the pure Kotlin in section 7 compiled with the Kotlin 2.4.10 compiler and its 24 new tests run under JUnit together with the S1b classes (mode state and the "two failed starts fall back to Develop" guard, the project catalogue scan, duplicate, rename, delete, new project sizing, strip planning, the strip assembly equal to a whole render, the matte for JPEG, a streaming PNG writer whose output was decoded independently in Python and by an in-test decoder, the index diff); the GPU strip render: the Studio shader rendered as two strips stitched equals the whole render byte for byte on Mesa (`bands.patch`); the Fs and golden patches apply to the tree (`git apply --check`). Not verified (written carefully, CI is the check): everything that needs Android, Compose, Room, Gradle: section 5.

## 1. Scope

In: Studio home (project grid with thumbnails, New with blank presets or one photo from the system picker, Open, Duplicate, Rename, Delete), the `Develop | Studio` mode switch in the two home top bars only, `studio.db` (Room) as a rebuildable index, flatten export to JPEG and PNG with Share and a destination picker, the `STUDIO_ENABLED` build flag with code absent when off, two crashes at start fall back to Develop, hidden mode releases GL and native memory, Studio section in the Copy report, CI builds and tests both flag values.
Out: Open in Studio from Develop (S2), project zip export and layered export (S9), the export foreground service (S9; S1c exports on a coroutine in the app process with a progress dialog and cancel), templates tab, Studio settings beyond a Reset switch.

## 2. Decisions (so the worker does not ask)

| # | Decision | Reason |
|---|---|---|
| D1 | The flag is a Gradle property `studioEnabled`, default read from a committed one line file `studio.enabled` at the repository root (initial content `false`); `-PstudioEnabled=true|false` overrides it. CI runs both values; turning Studio on for a release is a one line reviewed commit | Spec: default off in release until Jai agrees; no secrets or repository variables to tap. Jai can still try it: a debug build with `-PstudioEnabled=true` is what CI's matrix job produces, and the PM can ask for a one-off commit that flips the file. |
| D2 | With the flag off the Studio feature modules are not on the app's classpath and the app compiles a stub (`StudioEntry` in `src/studioOff`), so no Studio class is in the APK; a CI step greps the dex files to prove it. The native library still contains the compositor (about 100 KB) because CMake builds it either way | "No Studio class in the APK" from the spec's exit check. |
| D3 | `studio.db` is a Room database with one table, `exportSchema = true` and `fallbackToDestructiveMigration(dropAllTables = true)` | It only holds what the directories already say; a failed open or a schema change just rebuilds it from the scan. This is the one place in the app where a destructive fallback is right; Develop's database keeps its no-wipe rule. |
| D4 | Home shows the indexed rows at once, then scans the directories on a background thread and applies `IndexDiff` (the directories win); damaged or newer-format projects are listed as "Cannot open" with Duplicate and Delete disabled and Delete allowed | First paint is instant; a corrupt project never crashes the home. |
| D5 | Flatten export renders in full-width strips of at most 4 megapixels (`Flatten`), PNG is written by the streaming `PngWriter` (exact colour under partial alpha, no bitmap), JPEG is made from the matted strips with `Bitmap.compress` into one opaque ARGB_8888 bitmap (48 MB at 12 MP), transparent areas become white for JPEG | Bounded memory; Android's PNG path premultiplies and would change semi transparent colour. |
| D6 | Export colour space: sRGB documents embed no profile for JPEG (as Develop does), Display P3 documents embed the P3 profile in JPEG and PNG (reuse `IccProfiles` of core/render; `PngWriter(iccProfile = ...)` writes `iCCP`) | Matches the document's colour space. |
| D7 | Start guard: `ModeState.startMode()` counts a Studio start before it begins and `studioReady()` (called when the Studio home has drawn its first frame) resets it; two starts without a reset fall back to Develop once with the notice in the model | BK-409. |
| D8 | Leaving Studio releases its GL context and native memory (BK-399): `StudioHost` disposes the Studio GL view, which destroys the compositor on the GL thread (the `EditorSession.release()` pattern: `queueEvent`, then `StudioNative.destroy`; `abandon` if the context is already gone); projects stay on disk | Memory returns to Develop's budget. |
| D9 | New project from a photo: the picture is decoded with `DecodeCap` style limits (`NewProject.fit`), becomes layer 1 ("Photo"), and the canvas is its size; blank presets are in `NewProject.presets`; the canvas dialog shows the 12 MP cap | Spec 2.16 S1. |

## 3. Files and wiring

### Gradle and flag (not run here)
`studio.enabled` (repo root, one line):
```
false
```
`app/build.gradle.kts` additions:
```kotlin
val studioEnabled = (providers.gradleProperty("studioEnabled").orNull ?: rootProject.file("studio.enabled").takeIf { it.exists() }?.readText()?.trim() ?: "false") == "true"

android {
    defaultConfig {
        buildConfigField("boolean", "STUDIO_ENABLED", "$studioEnabled")
    }
    sourceSets.getByName("main").java.srcDir(if (studioEnabled) "src/studioOn/kotlin" else "src/studioOff/kotlin")
}

dependencies {
    if (studioEnabled) {
        implementation(project(":feature:studio"))
        implementation(project(":core:studio-render"))
    }
    implementation(project(":core:studio-model"))   // pure model, small; ModeState and the start guard live there
}
```
`settings.gradle.kts` includes `:feature:studio` and `:core:studio-render` always (so their unit tests and lint run in every build); only the app's dependency is conditional. Module `feature/studio/build.gradle.kts` follows `feature/editor/build.gradle.kts` (compose plugin, `implementation(project(":core:ui"))`, `project(":core:studio-render")`, `project(":core:studio-model")`, `libs.androidx.room.runtime`, `libs.androidx.room.ktx`, `ksp(libs.androidx.room.compiler)` with `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` as `core/data` does).

### App source sets
`app/src/studioOff/kotlin/app/rawline/StudioEntry.kt`:
```kotlin
package app.rawline
import androidx.compose.runtime.Composable
/** Flag off: Studio does not exist in this build. */
object StudioEntry {
    const val available = false
    @Composable fun Host(onSwitchToDevelop: () -> Unit, onReady: () -> Unit) {}
}
```
`app/src/studioOn/kotlin/app/rawline/StudioEntry.kt`: the same object with `available = true` whose `Host` calls `app.rawline.feature.studio.StudioRoot(onSwitchToDevelop, onReady)`.

### ModeHost (app, not compiled here)
`MainActivity.setContent` calls `ModeHost` instead of `RawlineRoot` directly:
```kotlin
@Composable fun ModeHost(openRoute: String?, onRouteConsumed: () -> Unit) {
    val prefs = remember { app.graph.prefs }                       // the existing "rawline" SharedPreferences
    val modeState = remember { ModeState(BuildConfig.STUDIO_ENABLED, PrefsKeyValue(prefs)) }
    var mode by rememberSaveable { mutableStateOf(modeState.startMode()) }
    val notice = remember { modeState.notice }                     // show once as a toast, then modeState.clearNotice()
    when (mode) {
        AppMode.DEVELOP -> RawlineRoot(openRoute, onRouteConsumed, modeSwitch = if (modeState.switchVisible(true)) ({ ModeToggle(AppMode.DEVELOP) { mode = modeState.switchTo(it) } }) else null)
        AppMode.STUDIO -> StudioEntry.Host(onSwitchToDevelop = { mode = modeState.switchTo(AppMode.DEVELOP) }, onReady = { modeState.studioReady() })
    }
}
class PrefsKeyValue(private val p: android.content.SharedPreferences) : KeyValue {
    override fun getString(key: String) = p.getString(key, null)
    override fun putString(key: String, value: String) { p.edit().putString(key, value).commit() }   // commit, not apply: the guard must survive a crash a moment later
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun putInt(key: String, value: Int) { p.edit().putInt(key, value).commit() }
}
```
`ModeToggle` is a two segment Lr control ("Develop", "Studio"), 48 dp tall, in the top bar of Develop's library screen (add an optional `modeSwitch: (@Composable () -> Unit)? = null` parameter to `LibraryScreen` and draw it at the start of the top bar when non null) and of the Studio home. It is never passed to the loupe, the editor or the canvas. The switch is visible only when `STUDIO_ENABLED` and the screen is a home.

### Studio home (feature/studio, not compiled here)
`StudioRoot` holds a `NavHost` with routes `studio/home` and `studio/canvas/{id}` (the canvas is S1b's screen). `StudioHomeViewModel` (all file work on `Dispatchers.IO`):
```kotlin
class StudioHomeViewModel(private val app: Application) : AndroidViewModel(app) {
    private val fs = JavaFs(app.filesDir)                          // paths are relative to filesDir: "files/studio/..." becomes "studio/..." (pass root = "studio")
    private val root = "studio"
    private val dao = StudioDb.get(app).projects()
    val rows = MutableStateFlow<List<ProjectRow>>(emptyList()); val damaged = MutableStateFlow<List<String>>(emptyList())
    init { viewModelScope.launch(Dispatchers.IO) {
        rows.value = dao.all().map { it.toRow() }                  // first paint from the index
        refresh()
    } }
    suspend fun refresh() {
        val scan = ProjectCatalog.scan(fs, root)                   // the directories win
        val diff = IndexDiff.compute(dao.all().map { it.toRow() }, scan.rows)
        if (!diff.isEmpty) { dao.upsert(diff.upserts.map { it.toEntity() }); dao.delete(diff.deletes) }
        rows.value = scan.rows; damaged.value = scan.damaged
    }
    fun create(doc: Document) = viewModelScope.launch(Dispatchers.IO) { ProjectStore(fs, "$root/${doc.id}").save(doc, { null }, setOf(doc.layers[0].common.id)); refresh() }
    fun duplicate(id: String) = viewModelScope.launch(Dispatchers.IO) { ProjectCatalog.duplicate(fs, root, id, ProjectCatalog.newId(System.currentTimeMillis()), System.currentTimeMillis()); refresh() }
    fun rename(id: String, name: String) = viewModelScope.launch(Dispatchers.IO) { ProjectCatalog.rename(fs, root, id, name, System.currentTimeMillis()); refresh() }
    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) { ProjectCatalog.delete(fs, root, id); dao.delete(listOf(id)); refresh() }
}
```
Note the root: `ProjectCatalog.ROOT` is `files/studio` for tests; on the phone the `JavaFs` base is `filesDir` and the root string is `studio` (so the directory is `filesDir/studio/{id}`). Use `root = "studio"` everywhere on the phone.
Home UI per spec section 5 (3 column grid, 2 dp gaps, thumbnail on black, name 12 sp, meta 11 sp grey, `{w} x {h}` and size), top bar 48 dp `#1C1C1C` with the mode toggle and a "New" button, bottom nav 56 dp (Projects only in S1c; Templates and Settings arrive later, do not draw empty tabs), empty state "No projects" with a New button, long press or overflow menu: Open, Duplicate, Rename, Delete (confirm: "Delete {name}? This cannot be undone."). New dialog: presets from `NewProject.presets` plus "From a photo" (system photo picker, `ActivityResultContracts.PickVisualMedia`), and the line "Canvas is limited to 12 megapixels for now." Thumbnails: `thumb.jpg` in the project directory, 512 px on the long edge, written by the canvas screen on leaving (S1b autosave calls `StudioExporter.thumbnail`), loaded on a worker with an LRU of 40.
Calls `onReady()` from a `LaunchedEffect(Unit)` after the first frame of the home (use `withFrameNanos`).

### studio.db (Room, not compiled here)
```kotlin
@Entity(tableName = "projects")
data class StudioProjectEntity(@PrimaryKey val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val layerCount: Int, val sizeBytes: Long, val hasThumb: Boolean)
@Dao interface StudioProjectDao {
    @Query("SELECT * FROM projects ORDER BY modified DESC, id") suspend fun all(): List<StudioProjectEntity>
    @Upsert suspend fun upsert(rows: List<StudioProjectEntity>)
    @Query("DELETE FROM projects WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
}
@Database(entities = [StudioProjectEntity::class], version = 1, exportSchema = true)
abstract class StudioDb : RoomDatabase() {
    abstract fun projects(): StudioProjectDao
    companion object {
        @Volatile private var inst: StudioDb? = null
        fun get(c: Context): StudioDb = inst ?: synchronized(this) { inst ?: Room.databaseBuilder(c.applicationContext, StudioDb::class.java, "studio.db").fallbackToDestructiveMigration(dropAllTables = true).build().also { inst = it } }
    }
}
```
Commit the generated `feature/studio/schemas/.../1.json`. Mapping functions `toRow()`/`toEntity()` are field by field. `studio.db` is not part of Develop's backup (spec 3.6).

### Flatten export (core/studio-render, not compiled here)
```kotlin
class StudioExporter(private val context: Context, private val session: StudioSession) {
    /** format: "png" or "jpg". Writes into [out]. Returns false when cancelled. Reports 0..1. */
    fun flatten(doc: Document, format: String, quality: Int, out: OutputStream, icc: ByteArray?, cancelled: () -> Boolean, progress: (Float) -> Unit): Boolean {
        val w = doc.width; val h = doc.height
        val png = if (format == "png") PngWriter(out, w, h, icc) else null
        val jpegBitmap = if (format == "jpg") Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        val canvas = jpegBitmap?.let { Canvas(it) }
        val ok = Flatten.run(w, h,
            render = { y, rows -> session.renderStrip(y, rows) },                      // GL thread hop inside: compositor.render(layers, 0f, y.toFloat(), 1f, w, rows)
            onStrip = { y, rows, px ->
                if (png != null) png.writeRows(px, rows)
                else { Flatten.matteOverWhite(px); val s = Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888); s.copyPixelsFromBuffer(ByteBuffer.wrap(px)); canvas!!.drawBitmap(s, 0f, y.toFloat(), null); s.recycle() }
                progress((y + rows).toFloat() / h)
            }, cancelled = cancelled)
        if (!ok) return false
        png?.finish()
        jpegBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, out)
        jpegBitmap?.recycle()
        return true
    }
}
```
`session.renderStrip(y, rows)` allocates a `ByteArray(w * rows * 4)` and calls `StudioNative.render(handle, layers, 0f, y.toFloat(), 1f, w, rows, out)` on the GL thread (the output size changes per strip, which reallocates the compositor's ping-pong pair: 8 bytes x 2 x the strip pixels, at most 64 MB; keep the strip size fixed except the last). Destination and Share reuse Develop's code path in `ExportRunner` for the SAF picker and the `ACTION_SEND` intent, with names from `ExportNaming`. Export sheet: bottom sheet (Lr `#262626`), format segmented (JPEG, PNG), quality slider (JPEG, 60 to 100, default 92), size shown, Export button (accent), inline progress with Cancel, result toast with Share. Transparent areas: a note "Transparent areas become white in a JPEG" when any layer has transparency (always shown for JPEG, one line).

### Copy report (app, `ReportBuilder.kt`)
Add a "Studio" block: `enabled yes or no (build flag)`, `mode`, `projects N`, `starts pending N`, `last fallback to Develop yes or no`, texture MB (`StudioNative.textureBytes`), the S1b timers, `studio_export_ms` (add), and "Edits by look" is W22's. No Studio code is referenced when the flag is off: put the block behind `StudioEntry.reportLines()` (stub returns an empty list).

### CI (`.github/workflows/build.yml`, not run here)
Add a job next to `lint`:
```yaml
  studio-flag:
    # Both values of the Studio flag build and pass the unit tests; with the flag off no Studio class is in the APK.
    if: github.event_name != 'schedule'
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    strategy:
      fail-fast: false
      matrix:
        studio: ["true", "false"]
    steps:
      - uses: actions/checkout@v7.0.1
      - uses: actions/setup-java@v6.0.1
        with:
          distribution: temurin
          java-version: "21"
      - uses: actions/cache@v6.1.0
        with:
          path: .android-sdk
          key: android-sdk-${{ hashFiles('tools/setup-sdk.sh') }}
      - uses: gradle/actions/setup-gradle@v6.4.0
      - name: Android SDK and NDK
        run: ./tools/setup-sdk.sh
      - name: Unit tests and debug build
        run: |
          source tools/env.sh
          ./gradlew testDebugUnitTest assembleDebug -PstudioEnabled=${{ matrix.studio }} --no-daemon
      - name: Studio code is absent when the flag is off
        if: matrix.studio == 'false'
        run: |
          apk=$(ls app/build/outputs/apk/debug/*.apk | head -1)
          n=$(unzip -p "$apk" 'classes*.dex' | grep -c "app/rawline/feature/studio" || true)
          echo "references to the Studio feature in the dex files: $n"
          test "$n" = "0"
```
(The grep counts lines of binary, which is enough for a present or absent check; if the first run shows a false positive from a string in the stub, narrow the pattern to `Lapp/rawline/feature/studio/`.) The main `build` job keeps using `studio.enabled` (false) until Jai agrees.

### Hidden mode releases memory (BK-399)
`StudioRoot` wraps the canvas GL view in a `DisposableEffect`; on dispose the session calls `release()` (idempotent): `glView.queueEvent { StudioNative.destroy(handle); handle = 0 }`, `onSurfaceCreated` after a context loss calls `StudioNative.abandon(handle)` first (S1b pattern). Leaving Studio for Develop disposes the whole `StudioRoot`. `onTrimMemory`: Studio drops history to 50 MB and, if the canvas is not visible, the compositor.

### docs
docs/DECISIONS.md: the flag mechanism, D3 (the destructive fallback is deliberate), D5/D6. docs/PERF.md rows "Studio export flatten 12 MP JPEG" and "Studio home first paint" stay "not measured". docs/STUDIO_SPEC.md S1 status updated.

## 4. Acceptance (the S1 exit criteria, with the checks)

1. `studio.enabled` is `false`: Develop is unchanged (existing goldens identical, lint finding count not increased, the `studio-flag` job's grep finds 0 Studio references); `-PstudioEnabled=true` builds and the switch appears only in the two home top bars (never in the loupe, editor or canvas).
2. Studio home: New (4 presets and From a photo), Open, Duplicate, Rename, Delete; a damaged project is listed as "Cannot open" and the rest still list (`CatalogTest`).
3. Flatten export to JPEG and PNG through Share and a destination; PNG keeps exact colour under partial alpha; JPEG has white where the canvas was transparent; both decode back (JPEG by the platform, PNG by the in-test decoder); export of a 12 MP, 10 layer project stays under the memory guard (`studio_texture_mb`, heap flat).
4. Two Studio starts that never draw the home fall back to Develop with the notice (`ModeStateTest`; on the phone: force stop twice during the splash with a debug setting that crashes on Studio start).
5. Switching Develop to Studio and back 20 times: native heap in the Copy report returns to the Develop value and `studio_texture_mb` is 0 in Develop.
6. CI: `studio-flag` job green for both values; host tests green; `studio-golden.sh` includes the strip equality line.
Jai on the phone (one message): make a project from a photo, paint, export a PNG and a JPEG and open them in Gallery; duplicate and delete a project; switch to Develop and back; force stop during a stroke, reopen; paste the Copy report.

## 5. Not verified here

Room generation and the schema JSON, the Compose screens, `PrefsKeyValue` and the activity wiring, the dex grep, the photo picker import, ICC embedding through `IccProfiles`, thumbnail writing and every phone number. The pure behaviour each of these relies on is covered by the tests in section 7.

## 6. Housekeeping found while reading the tree

`tools/studio/__pycache__/studio_ref.cpython-311.pyc` is committed (delete it and add `__pycache__/` and `*.pyc` to `.gitignore`), and an untracked directory with a code fence in its name sits next to `Blend.kt` under `core/studio-model/src/main/kotlin/.../model/` (a mis-parsed `mkdir`; delete it).

## 7. Tested source (copy verbatim into core/studio-model; Fs additions are a patch)

### fs.patch (adds `dirs`, `size`, `deleteTree` to `Fs`, `JavaFs` and the test `MemFs`; `git apply --check` passes)
```diff
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/ProjectStore.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/ProjectStore.kt
@@ -16,6 +16,12 @@
     fun delete(path: String)
     /** File names (not paths) directly inside [dir]. */
     fun list(dir: String): List<String>
+    /** Names of the sub directories directly inside [dir]. */
+    fun dirs(dir: String): List<String>
+    /** Size in bytes, 0 when missing. */
+    fun size(path: String): Long
+    /** Deletes [path] and everything under it. */
+    fun deleteTree(path: String)
 }
 
 /** Straight RGBA8 pixels of one layer. */
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/JavaFs.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/JavaFs.kt
@@ -23,5 +23,8 @@
     }
 
     override fun delete(path: String) { f(path).delete() }
-    override fun list(dir: String): List<String> = f(dir).list()?.toList() ?: emptyList()
+    override fun list(dir: String): List<String> = f(dir).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
+    override fun dirs(dir: String): List<String> = f(dir).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
+    override fun size(path: String): Long = f(path).takeIf { it.isFile }?.length() ?: 0L
+    override fun deleteTree(path: String) { f(path).deleteRecursively() }
 }
--- a/core/studio-model/src/test/kotlin/app/rawline/core/studio/model/ProjectStoreTest.kt
+++ b/core/studio-model/src/test/kotlin/app/rawline/core/studio/model/ProjectStoreTest.kt
@@ -19,6 +19,9 @@
     override fun rename(from: String, to: String) { files[to] = files.remove(from) ?: throw IllegalStateException("no $from") }
     override fun delete(path: String) { files.remove(path) }
     override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1) }
+    override fun dirs(dir: String) = files.keys.filter { it.startsWith("$dir/") && it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1).substringBefore('/') }.distinct()
+    override fun size(path: String) = files[path]?.size?.toLong() ?: 0L
+    override fun deleteTree(path: String) { files.keys.removeAll { it == path || it.startsWith("$path/") } }
 }
 
 class Crash : RuntimeException("simulated kill")
```
### Mode.kt (AppMode, KeyValue, ModeState, StartGuard)
```kotlin
package app.rawline.core.studio.model

/** The two products inside the app (spec 3.5). */
enum class AppMode(val key: String) { DEVELOP("develop"), STUDIO("studio");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: DEVELOP }
}

/** The few persisted values the mode switch needs. SharedPreferences on Android, a map in tests. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class MapKeyValue : KeyValue {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
}

/**
 * Which mode the app opens in and when the switch is shown (spec 3.5). With the flag off Studio does not exist: the stored mode is ignored, the
 * switch is never shown. The switch is shown only on the two home screens, never while editing or painting.
 */
class ModeState(private val studioEnabled: Boolean, private val kv: KeyValue) {
    private val guard = StartGuard(kv)
    var notice: String? = null
        private set

    /** The mode to start in. A Studio start that crashed twice in a row falls back to Develop and says so once. */
    fun startMode(): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        val wanted = AppMode.fromKey(kv.getString(KEY))
        if (wanted == AppMode.STUDIO) {
            if (guard.shouldFallBack()) { kv.putString(KEY, AppMode.DEVELOP.key); notice = "Studio did not start twice, so Rawline opened Develop. Switch to Studio again when you like."; return AppMode.DEVELOP }
            guard.beginStudioStart()
        }
        return wanted
    }

    /** Call when the Studio home has drawn its first frame. */
    fun studioReady() = guard.studioReady()

    fun switchVisible(onHomeScreen: Boolean) = studioEnabled && onHomeScreen

    fun switchTo(mode: AppMode): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        kv.putString(KEY, mode.key)
        if (mode == AppMode.STUDIO) guard.beginStudioStart() else guard.studioReady()
        return mode
    }

    fun clearNotice() { notice = null }

    companion object { const val KEY = "mode" }
}

/** Counts Studio starts that did not reach the first frame. [limit] in a row means the next start goes to Develop (BK-409). */
class StartGuard(private val kv: KeyValue, private val limit: Int = 2) {
    fun beginStudioStart() = kv.putInt(PENDING, kv.getInt(PENDING, 0) + 1)
    fun studioReady() = kv.putInt(PENDING, 0)
    fun shouldFallBack(): Boolean { val n = kv.getInt(PENDING, 0); if (n >= limit) { kv.putInt(PENDING, 0); return true }; return false }
    companion object { const val PENDING = "studio_pending_starts" }
}
```
### Catalog.kt (ProjectRow, ProjectCatalog, NewProject, IndexDiff)
```kotlin
package app.rawline.core.studio.model

import java.util.Random

/** One row of the Studio home grid and of studio.db. Everything here can be rebuilt from the project directories. */
data class ProjectRow(val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val layerCount: Int, val sizeBytes: Long, val hasThumb: Boolean)

class ScanResult(val rows: List<ProjectRow>, val damaged: List<String>)

/**
 * The project directories under [root] (`files/studio`) are the source of truth (spec 3.6); this reads them. Every operation works
 * on an [Fs], so the same code runs on the phone and in the host tests.
 */
object ProjectCatalog {
    const val ROOT = "files/studio"

    /** Newest first. A directory that does not open (damaged, newer format) is reported by id, never crashes the home screen. */
    fun scan(fs: Fs, root: String = ROOT): ScanResult {
        val rows = ArrayList<ProjectRow>(); val bad = ArrayList<String>()
        for (id in fs.dirs(root).sorted()) {
            try { rows += row(fs, "$root/$id", id) } catch (e: ProjectFormatException) { bad += id } catch (e: NewerSchemaException) { bad += id }
        }
        rows.sortWith(compareByDescending<ProjectRow> { it.modified }.thenBy { it.id })
        return ScanResult(rows, bad)
    }

    private fun row(fs: Fs, dir: String, id: String): ProjectRow {
        val doc = ProjectStore(fs, dir).open().document
        val size = fs.list(dir).sumOf { fs.size("$dir/$it") } + fs.list("$dir/layers").sumOf { fs.size("$dir/layers/$it") }
        return ProjectRow(id, doc.name, doc.width, doc.height, doc.modified, doc.layers.size, size, fs.exists("$dir/thumb.jpg"))
    }

    /** "p" + time in base 36 + 4 random base 36 characters: unique enough on one phone, sortable by creation, safe as a directory name. */
    fun newId(nowMs: Long, rnd: Random = Random()): String = "p" + nowMs.toString(36) + (0 until 4).map { "0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)] }.joinToString("")

    /** Copies every file (layer files are named by content, so they are shared by value), then rewrites the copy's id and name. */
    fun duplicate(fs: Fs, root: String, id: String, newId: String, nowMs: Long): ProjectRow {
        require(!fs.exists("$root/$newId/project.json") && fs.dirs(root).none { it == newId }) { "project $newId exists" }
        val src = "$root/$id"; val dst = "$root/$newId"
        for (n in fs.list(src)) if (!n.endsWith(".tmp")) fs.write("$dst/$n", fs.read("$src/$n")!!)
        for (n in fs.list("$src/layers")) if (!n.endsWith(".tmp")) fs.write("$dst/layers/$n", fs.read("$src/layers/$n")!!)
        val store = ProjectStore(fs, dst)
        val doc = store.open().document
        store.save(doc.copy(id = newId, name = (doc.name + " copy").take(60), modified = nowMs), { null }, emptySet())
        return row(fs, dst, newId)
    }

    fun rename(fs: Fs, root: String, id: String, name: String, nowMs: Long) {
        val store = ProjectStore(fs, "$root/$id")
        val doc = store.open().document
        store.save(doc.copy(name = name.trim().take(60).ifEmpty { doc.name }, modified = nowMs), { null }, emptySet())
    }

    fun delete(fs: Fs, root: String, id: String) { require(id.isNotEmpty() && !id.contains('/') && !id.contains("..")) { "bad id" }; fs.deleteTree("$root/$id") }
}

/** New project choices (spec 2.16 S1: blank presets and one photo). The canvas is capped at 12 MP and 8192 on a side. */
object NewProject {
    class Preset(val label: String, val width: Int, val height: Int)
    val presets = listOf(Preset("Square 1080", 1080, 1080), Preset("Portrait 1080 x 1350", 1080, 1350), Preset("Landscape 1920 x 1080", 1920, 1080), Preset("12 MP 4000 x 3000", 4000, 3000))

    /** Largest size with the same shape that fits the S1 caps. */
    fun fit(w: Int, h: Int): Pair<Int, Int> {
        require(w > 0 && h > 0)
        var s = 1.0
        if (w.toLong() * h > Document.MAX_PIXELS_S1) s = minOf(s, Math.sqrt(Document.MAX_PIXELS_S1.toDouble() / (w.toDouble() * h)))
        if (maxOf(w, h) > Document.MAX_EDGE) s = minOf(s, Document.MAX_EDGE.toDouble() / maxOf(w, h))
        var fw = maxOf(1, Math.floor(w * s).toInt()); var fh = maxOf(1, Math.floor(h * s).toInt())
        while (fw.toLong() * fh > Document.MAX_PIXELS_S1) { if (fw >= fh) fw-- else fh-- }
        return fw to fh
    }

    /** A new document with one empty (transparent) layer; the photo case replaces that layer's pixels with the scaled picture. */
    fun blank(id: String, name: String, w: Int, h: Int, nowMs: Long): Document {
        val (fw, fh) = fit(w, h)
        return Document(id, name, fw, fh, layers = listOf(Layer.Pixel(LayerCommon("l1", "Layer 1"), fw, fh)), created = nowMs, modified = nowMs)
    }
}

/** What to change in studio.db so that it matches a scan of the directories (the directories win, the index is only a fast first paint). */
class IndexDiff(val upserts: List<ProjectRow>, val deletes: List<String>) {
    val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
    companion object {
        fun compute(indexed: List<ProjectRow>, scanned: List<ProjectRow>): IndexDiff {
            val old = indexed.associateBy { it.id }; val now = scanned.associateBy { it.id }
            return IndexDiff(scanned.filter { old[it.id] != it }, indexed.filter { it.id !in now }.map { it.id })
        }
    }
}
```
### Flatten.kt (Flatten, PngWriter)
```kotlin
package app.rawline.core.studio.model

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Flatten export (spec 2.18 S1: JPEG and PNG). The picture is rendered in full-width strips so memory stays bounded whatever the canvas
 * size (a strip is at most 4 megapixels), the same idea as Develop's tiled export. Each strip is the compositor's straight RGBA8 for
 * `view = (0, y)` at zoom 1.
 */
object Flatten {
    const val MAX_STRIP_PIXELS = 4_000_000

    fun stripRows(w: Int, h: Int) = minOf(h, maxOf(16, MAX_STRIP_PIXELS / w))

    /** Strips as (y, rows) covering 0 until h exactly once. */
    fun strips(w: Int, h: Int, rows: Int = stripRows(w, h)): List<IntArray> {
        return (0 until h step rows).map { y -> intArrayOf(y, minOf(rows, h - y)) }
    }

    /** Renders every strip in order. Returns false when [cancelled] stopped it (nothing half written is the caller's to delete). */
    fun run(w: Int, h: Int, render: (y: Int, rows: Int) -> ByteArray, onStrip: (y: Int, rows: Int, rgba: ByteArray) -> Unit, cancelled: () -> Boolean = { false }, stripRows: Int = stripRows(w, h)): Boolean {
        for ((y, rows) in strips(w, h, stripRows)) {
            if (cancelled()) return false
            val px = render(y, rows)
            require(px.size == w * rows * 4) { "strip is ${px.size} bytes, expected ${w * rows * 4}" }
            onStrip(y, rows, px)
        }
        return true
    }

    /** JPEG has no alpha: transparent areas become white (the colour is straight, so c * a + 255 * (1 - a)). In place; alpha becomes 255. */
    fun matteOverWhite(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            val a = rgba[i + 3].toInt() and 255
            if (a != 255) {
                for (c in 0..2) { val v = rgba[i + c].toInt() and 255; rgba[i + c] = ((v * a + 255 * (255 - a) + 127) / 255).toByte() }
                rgba[i + 3] = 255.toByte()
            }
            i += 4
        }
    }
}

/**
 * Streaming PNG writer for straight RGBA8 (colour type 6, filter Sub). It never builds a bitmap, so semi transparent pixels keep their exact
 * colour (Android's Bitmap.compress premultiplies first and loses it), and memory is one strip plus the deflater. Rows are written as strips arrive.
 */
class PngWriter(private val out: OutputStream, private val width: Int, private val height: Int, private val iccProfile: ByteArray? = null) {
    private val deflater = Deflater(4)
    private val buf = ByteArray(64 * 1024)
    private var rowsWritten = 0
    private var finished = false

    init {
        require(width > 0 && height > 0)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array())
        if (iccProfile != null) {   // iCCP: name, 0, compression method 0, deflated profile
            val d = Deflater(); d.setInput(iccProfile); d.finish()
            val z = java.io.ByteArrayOutputStream(); val b = ByteArray(4096); while (!d.finished()) z.write(b, 0, d.deflate(b)); d.end()
            chunk("iCCP", "ICC".toByteArray() + byteArrayOf(0, 0) + z.toByteArray())
        }
    }

    /** [rows] rows of straight RGBA8, in order from the top. */
    fun writeRows(rgba: ByteArray, rows: Int) {
        check(!finished && rowsWritten + rows <= height)
        val stride = width * 4
        val line = ByteArray(stride + 1)
        for (r in 0 until rows) {
            line[0] = 1   // Sub: each byte minus the byte one pixel to the left
            val o = r * stride
            for (i in 0 until stride) line[1 + i] = (rgba[o + i] - (if (i >= 4) rgba[o + i - 4] else 0)).toByte()
            deflater.setInput(line, 0, line.size)
            while (!deflater.needsInput()) { val n = deflater.deflate(buf); if (n > 0) chunk("IDAT", buf.copyOf(n)) else break }
        }
        rowsWritten += rows
    }

    fun finish() {
        check(rowsWritten == height) { "wrote $rowsWritten of $height rows" }
        deflater.finish()
        while (!deflater.finished()) { val n = deflater.deflate(buf); if (n > 0) chunk("IDAT", buf.copyOf(n)) }
        deflater.end()
        chunk("IEND", ByteArray(0))
        finished = true
        out.flush()
    }

    private fun chunk(type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
```
### S1cTest.kt (24 tests)
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater

class ModeStateTest {
    @Test fun withTheFlagOffStudioDoesNotExist() {
        val kv = MapKeyValue(); kv.putString(ModeState.KEY, "studio")
        val m = ModeState(false, kv)
        assertEquals(AppMode.DEVELOP, m.startMode()); assertFalse(m.switchVisible(true)); assertEquals(AppMode.DEVELOP, m.switchTo(AppMode.STUDIO))
        assertEquals("studio", kv.getString(ModeState.KEY))   // the stored choice is left alone, so turning the flag on later restores it
    }

    @Test fun theSwitchShowsOnlyOnHomeScreens() {
        val m = ModeState(true, MapKeyValue())
        assertTrue(m.switchVisible(true)); assertFalse(m.switchVisible(false))
    }

    @Test fun theChosenModeIsRemembered() {
        val kv = MapKeyValue()
        ModeState(true, kv).switchTo(AppMode.STUDIO)
        assertEquals(AppMode.STUDIO, ModeState(true, kv).startMode())
        ModeState(true, kv).switchTo(AppMode.DEVELOP)
        assertEquals(AppMode.DEVELOP, ModeState(true, kv).startMode())
    }

    @Test fun twoStartsThatNeverReachTheFirstFrameFallBackToDevelop() {
        val kv = MapKeyValue(); ModeState(true, kv).switchTo(AppMode.STUDIO)   // 1st start attempt counted by the switch
        assertEquals(AppMode.STUDIO, ModeState(true, kv).startMode())           // process died before studioReady: 2nd attempt
        val third = ModeState(true, kv)
        assertEquals(AppMode.DEVELOP, third.startMode())
        assertTrue(third.notice!!.contains("did not start twice"))
        assertEquals(AppMode.DEVELOP, ModeState(true, kv).startMode())          // and stays in Develop until the user switches again
    }

    @Test fun reachingTheFirstFrameResetsTheCount() {
        val kv = MapKeyValue(); ModeState(true, kv).switchTo(AppMode.STUDIO)
        repeat(5) { val m = ModeState(true, kv); assertEquals(AppMode.STUDIO, m.startMode()); m.studioReady() }
    }
}

class CatalogTest {
    private val root = ProjectCatalog.ROOT
    private fun px(seed: Int) = RawPixels(8, 6, ByteArray(8 * 6 * 4).also { Random(seed.toLong()).nextBytes(it) })
    private fun make(fs: Fs, id: String, name: String, modified: Long) {
        val d = Document(id, name, 8, 6, layers = listOf(Layer.Pixel(LayerCommon("l1", "L1"), 8, 6), Layer.Pixel(LayerCommon("l2", "L2"), 8, 6)), modified = modified)
        ProjectStore(fs, "$root/$id").save(d, { px(it.common.id.hashCode()) }, setOf("l1", "l2"))
    }

    @Test fun scanListsNewestFirstWithSizeAndLayerCount() {
        val fs = MemFs(); make(fs, "a", "Old", 10); make(fs, "b", "New", 20)
        val r = ProjectCatalog.scan(fs)
        assertEquals(listOf("b", "a"), r.rows.map { it.id }); assertEquals("New", r.rows[0].name)
        assertEquals(2, r.rows[0].layerCount); assertTrue(r.rows[0].sizeBytes > 0); assertTrue(r.damaged.isEmpty())
    }

    @Test fun aDamagedOrNewerProjectIsReportedAndTheRestStillList() {
        val fs = MemFs(); make(fs, "a", "Good", 10); make(fs, "bad", "Bad", 5); make(fs, "newer", "Newer", 6)
        fs.files.keys.filter { it.startsWith("$root/bad/") }.forEach { fs.files[it] = "x".toByteArray() }
        fs.files["$root/newer/project.json"] = String(fs.files["$root/newer/project.json"]!!).replace("\"schemaVersion\": 1", "\"schemaVersion\": 3").toByteArray()
        val r = ProjectCatalog.scan(fs)
        assertEquals(listOf("a"), r.rows.map { it.id }); assertEquals(setOf("bad", "newer"), r.damaged.toSet())
    }

    @Test fun duplicateIsAnIndependentCopyWithTheSamePixels() {
        val fs = MemFs(); make(fs, "a", "Holiday", 10)
        val row = ProjectCatalog.duplicate(fs, root, "a", "b", 99)
        assertEquals("Holiday copy", row.name); assertEquals(99L, row.modified); assertEquals(2, row.layerCount)
        val orig = ProjectStore(fs, "$root/a"); val copy = ProjectStore(fs, "$root/b")
        val dOrig = orig.open().document; val dCopy = copy.open().document
        assertEquals("b", dCopy.id)
        for (i in dOrig.layers.indices) assertArrayEquals(orig.load(dOrig.layers[i] as Layer.Pixel)!!.rgba, copy.load(dCopy.layers[i] as Layer.Pixel)!!.rgba)
        ProjectCatalog.delete(fs, root, "a")
        assertEquals(listOf("b"), ProjectCatalog.scan(fs).rows.map { it.id })                           // deleting the original leaves the copy intact
        copy.load(copy.open().document.layers[0] as Layer.Pixel)
    }

    @Test fun renameKeepsPixelsAndRefusesAnEmptyName() {
        val fs = MemFs(); make(fs, "a", "Old", 10)
        ProjectCatalog.rename(fs, root, "a", "  New name  ", 50)
        assertEquals("New name", ProjectCatalog.scan(fs).rows.single().name)
        ProjectCatalog.rename(fs, root, "a", "   ", 60)
        assertEquals("New name", ProjectCatalog.scan(fs).rows.single().name)
        val s = ProjectStore(fs, "$root/a"); s.load(s.open().document.layers[0] as Layer.Pixel)
    }

    @Test fun deleteRemovesTheWholeDirectoryAndRefusesBadIds() {
        val fs = MemFs(); make(fs, "a", "A", 1); make(fs, "b", "B", 2)
        ProjectCatalog.delete(fs, root, "a")
        assertTrue(fs.files.keys.none { it.startsWith("$root/a/") }); assertTrue(fs.files.keys.any { it.startsWith("$root/b/") })
        for (bad in listOf("", "../x", "a/b")) try { ProjectCatalog.delete(fs, root, bad); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun idsAreDirectoryNamesAndDifferByTimeAndChance() {
        val a = ProjectCatalog.newId(1_759_700_000_000, Random(1)); val b = ProjectCatalog.newId(1_759_700_000_000, Random(2))
        assertTrue(a.matches(Regex("p[0-9a-z]+"))); assertNotEquals(a, b)
        assertTrue(ProjectCatalog.newId(1_759_700_000_001, Random(1)) > a.substring(0, 1))
    }
}

class NewProjectTest {
    @Test fun smallCanvasesAreUntouchedAndBigOnesFitTheCaps() {
        assertEquals(1080 to 1350, NewProject.fit(1080, 1350))
        assertEquals(4000 to 3000, NewProject.fit(4000, 3000))                           // exactly 12 MP
        val (w, h) = NewProject.fit(6000, 4000)                                           // 24 MP: scaled to fit 12 MP, shape kept
        assertTrue(w.toLong() * h <= 12_000_000L); assertEquals(1.5, w.toDouble() / h, 0.01)
        val (pw, ph) = NewProject.fit(30000, 100)                                         // a panorama: the edge cap decides
        assertEquals(8192, pw); assertTrue(ph >= 1)
    }

    @Test fun presetsAllFit() { for (p in NewProject.presets) assertEquals(p.width to p.height, NewProject.fit(p.width, p.height)) }

    @Test fun blankHasOneEmptyLayerAndTheRightSize() {
        val d = NewProject.blank("p1", "Untitled", 6000, 4000, 5)
        assertEquals(1, d.layers.size); assertNull((d.layers[0] as Layer.Pixel).pixelsFile)
        assertTrue(d.width.toLong() * d.height <= Document.MAX_PIXELS_S1); assertEquals(d.width, (d.layers[0] as Layer.Pixel).width)
    }
}

class FlattenTest {
    private fun scene(w: Int, h: Int): List<RefLayer> {
        val rnd = Random(8)
        fun img(iw: Int, ih: Int, alpha: Boolean) = RefImage(iw, ih, ByteArray(iw * ih * 4).also { rnd.nextBytes(it); if (!alpha) for (i in 3 until it.size step 4) it[i] = 255.toByte() })
        return listOf(RefLayer(img(w, h, false), 0f, 0f, 1f, 1f, BlendMode.NORMAL), RefLayer(img(w / 2, h / 2, true), 5f, 7f, 1.5f, 0.8f, BlendMode.MULTIPLY), RefLayer(img(20, 20, true), 30f, 2f, 1f, 0.6f, BlendMode.SCREEN))
    }

    @Test fun stripsCoverTheCanvasOnceAndStayUnderTheStripCap() {
        for ((w, h) in listOf(1080 to 1350, 8192 to 1464, 4000 to 3000, 100 to 100000)) {
            val s = Flatten.strips(w, h)
            assertEquals(0, s.first()[0]); assertEquals(h, s.last()[0] + s.last()[1]); assertEquals(h, s.sumOf { it[1] })
            for (i in 1 until s.size) assertEquals(s[i - 1][0] + s[i - 1][1], s[i][0])
            assertTrue(s.all { w.toLong() * it[1] <= maxOf(Flatten.MAX_STRIP_PIXELS.toLong(), w.toLong() * 16) })
        }
    }

    @Test fun stripsAssembleToExactlyTheWholeRender() {
        val w = 90; val h = 70; val layers = scene(w, h)
        val whole = ReferenceCompositor.render(layers, 0f, 0f, 1f, w, h)
        val out = ByteArray(w * h * 4)
        Flatten.run(w, h, { y, rows -> ReferenceCompositor.render(layers, 0f, y.toFloat(), 1f, w, rows) }, { y, rows, px -> System.arraycopy(px, 0, out, y * w * 4, rows * w * 4) }, stripRows = 13)
        assertArrayEquals(whole, out)
    }

    @Test fun cancelStopsBetweenStrips() {
        var n = 0
        val done = Flatten.run(10, 100, { _, rows -> ByteArray(10 * rows * 4) }, { _, _, _ -> n++ }, cancelled = { n >= 2 }, stripRows = 10)
        assertFalse(done); assertEquals(2, n)
    }

    @Test fun matteOverWhiteMixesAndMakesOpaque() {
        val px = byteArrayOf(0, 0, 0, 0, 10, 20, 30, 255.toByte(), 100, 100, 100, 128.toByte())
        Flatten.matteOverWhite(px)
        assertEquals(listOf(255, 255, 255, 255), (0..3).map { px[it].toInt() and 255 })      // fully transparent: white
        assertEquals(listOf(10, 20, 30, 255), (4..7).map { px[it].toInt() and 255 })         // opaque: unchanged
        assertEquals(listOf(177, 177, 177, 255), (8..11).map { px[it].toInt() and 255 })     // (100 * 128 + 255 * 127) / 255 = 177
    }
}

class PngWriterTest {
    /** A small independent decoder: signature, every chunk CRC, IHDR, IDAT inflated and un-filtered. */
    private fun decode(png: ByteArray): Triple<Int, Int, ByteArray> {
        val b = java.nio.ByteBuffer.wrap(png)
        val sig = ByteArray(8); b.get(sig)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), sig)
        var w = 0; var h = 0; val idat = ByteArrayOutputStream(); var ended = false; var sawIccp = false
        while (b.remaining() > 0) {
            val len = b.getInt(); val t = ByteArray(4); b.get(t); val d = ByteArray(len); b.get(d); val crc = b.getInt()
            val c = CRC32(); c.update(t); c.update(d); assertEquals("crc of ${String(t)}", c.value.toInt(), crc)
            when (String(t)) { "IHDR" -> { val bb = java.nio.ByteBuffer.wrap(d); w = bb.getInt(); h = bb.getInt(); assertEquals(8, d[8].toInt()); assertEquals(6, d[9].toInt()) }; "IDAT" -> idat.write(d); "IEND" -> ended = true; "iCCP" -> sawIccp = true }
        }
        assertTrue(ended)
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        val raw = ByteArray((w * 4 + 1) * h); var n = 0; while (n < raw.size) { val k = inf.inflate(raw, n, raw.size - n); if (k == 0) break; n += k }
        assertEquals(raw.size, n)
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val f = raw[y * (w * 4 + 1)].toInt(); assertEquals(1, f)
            for (i in 0 until w * 4) out[y * w * 4 + i] = (raw[y * (w * 4 + 1) + 1 + i] + (if (i >= 4) out[y * w * 4 + i - 4] else 0)).toByte()
        }
        return Triple(w, h, out)
    }

    @Test fun roundTripsEveryByteIncludingSemiTransparentColourAndStripBoundaries() {
        val w = 37; val h = 29; val rgba = ByteArray(w * h * 4).also { Random(3).nextBytes(it) }
        for (i in 0 until w * h step 5) { rgba[i * 4 + 3] = 0 }                  // alpha 0 pixels keep their colour in the file (the writer is exact)
        val bos = ByteArrayOutputStream(); val pw = PngWriter(bos, w, h)
        var y = 0; for (rows in intArrayOf(10, 10, 9)) { pw.writeRows(rgba.copyOfRange(y * w * 4, (y + rows) * w * 4), rows); y += rows }
        pw.finish()
        val (dw, dh, px) = decode(bos.toByteArray())
        assertEquals(w, dw); assertEquals(h, dh); assertArrayEquals(rgba, px)
    }

    @Test fun anIncompleteImageCannotBeFinished() {
        val pw = PngWriter(ByteArrayOutputStream(), 4, 4); pw.writeRows(ByteArray(4 * 4 * 2), 2)
        try { pw.finish(); org.junit.Assert.fail() } catch (e: IllegalStateException) {}
    }

    @Test fun anIccProfileIsEmbedded() {
        val bos = ByteArrayOutputStream(); val pw = PngWriter(bos, 2, 1, iccProfile = ByteArray(300) { it.toByte() })
        pw.writeRows(ByteArray(8), 1); pw.finish()
        decode(bos.toByteArray())
        assertTrue(String(bos.toByteArray(), Charsets.ISO_8859_1).contains("iCCP"))
    }
}

class IndexDiffTest {
    private fun row(id: String, modified: Long = 1, size: Long = 10) = ProjectRow(id, id, 8, 6, modified, 2, size, false)

    @Test fun anIdenticalIndexNeedsNoChange() { assertTrue(IndexDiff.compute(listOf(row("a"), row("b")), listOf(row("b"), row("a"))).isEmpty) }

    @Test fun newChangedAndVanishedProjectsAreFound() {
        val d = IndexDiff.compute(listOf(row("a"), row("b", modified = 1), row("gone")), listOf(row("a"), row("b", modified = 2), row("new")))
        assertEquals(setOf("b", "new"), d.upserts.map { it.id }.toSet()); assertEquals(listOf("gone"), d.deletes)
    }

    @Test fun aWipedIndexIsRebuiltFromTheDirectories() {
        val fs = MemFs()
        for (id in listOf("a", "b")) ProjectStore(fs, "${ProjectCatalog.ROOT}/$id").save(Document(id, id, 8, 6, layers = listOf(Layer.Pixel(LayerCommon("l", "l"), 8, 6)), modified = 5), { null }, setOf("l"))
        val d = IndexDiff.compute(emptyList(), ProjectCatalog.scan(fs).rows)
        assertEquals(2, d.upserts.size); assertTrue(d.deletes.isEmpty())
    }
}
```
### bands.patch (the strip equality in the Studio golden)
```diff
--- a/tools/studio/studio_scene.py
+++ b/tools/studio/studio_scene.py
@@ -40,7 +40,8 @@
     ("screen", screen_layer, 90, 50, 1.5, 0.6, 2),
     ("normal", normal_layer, 10, 120, 1.0, 1.0, 0),
 ]
-VIEWS = {"a": (0.0, 0.0, 1.0, 256, 192), "b": (64.0, 48.0, 2.0, 256, 192), "c": (-20.0, -10.0, 1.0, 200, 150)}
+VIEWS = {"a": (0.0, 0.0, 1.0, 256, 192), "b": (64.0, 48.0, 2.0, 256, 192), "c": (-20.0, -10.0, 1.0, 200, 150),
+         "band1": (0.0, 0.0, 1.0, 256, 96), "band2": (0.0, 96.0, 1.0, 256, 96)}   # band1 and band2 are the strips of a flatten export: stitched they must equal view a EXACTLY
 
 def make(d):
     os.makedirs(d, exist_ok=True)
@@ -67,6 +68,11 @@
         ok = worst <= tol
         print("%s  studio_blend3 view %s (zoom %g): worst %d level(s), %d of %d values differ  (tolerance %d)" % ("PASS" if ok else "FAIL", key, z, worst, off, len(exp), tol))
         fails += 0 if ok else 1
+    a = open(os.path.join(d, "out_a.rgba"), "rb").read()
+    stitched = open(os.path.join(d, "out_band1.rgba"), "rb").read() + open(os.path.join(d, "out_band2.rgba"), "rb").read()
+    same = stitched == a
+    print("%s  studio_blend3 flatten strips stitched equal the whole render byte for byte" % ("PASS" if same else "FAIL"))
+    fails += 0 if same else 1
     return fails
 
 def small(path):
--- a/tools/golden/studio-golden.sh
+++ b/tools/golden/studio-golden.sh
@@ -9,7 +9,7 @@
 cmake -DSHADER_DIR="$C/shaders" -DOUT="$W/shader_sources.h" -P "$C/gen_shaders.cmake"
 g++ -O1 -std=c++17 -I"$W" -I"$C" "$ROOT/tools/golden/studio_golden.cpp" "$C/studio/studio_compositor.cpp" -lEGL -lGLESv2 -o "$W/studio_golden"
 python3 "$ROOT/tools/studio/studio_scene.py" make "$W"
-for k in a b c; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
+for k in a b c band1 band2; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
 python3 "$ROOT/tools/studio/studio_scene.py" compare "$W"
 
 # S1b: the live stroke (stamps into the R16F stroke buffer, shown through the compositor) against the Python reference that bakes the stroke
```
