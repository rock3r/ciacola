package dev.sebastiano.ciacola.decks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.easel.Easel
import dev.bnorm.storyboard.easel.SceneMode
import dev.bnorm.storyboard.easel.rememberAnimatic
import dev.bnorm.storyboard.easel.resolveStoryboardWorkspaceRoot
import dev.sebastiano.ciacola.core.fonts.BundledFontCatalog
import dev.sebastiano.ciacola.core.fonts.ClasspathFontResourceLoader
import dev.sebastiano.ciacola.core.fonts.DeckFontResolver
import dev.sebastiano.ciacola.core.fonts.SystemFontLookup
import dev.sebastiano.ciacola.document.BuildEffectRegistry
import dev.sebastiano.ciacola.document.CanonicalJson
import dev.sebastiano.ciacola.document.DeckDecodeResult
import dev.sebastiano.ciacola.document.DeckDocument
import dev.sebastiano.ciacola.document.DeckDocumentCodec
import dev.sebastiano.ciacola.document.JsonMergePatch
import dev.sebastiano.ciacola.document.LegacyOverridesImporter
import dev.sebastiano.ciacola.document.RenderSession
import dev.sebastiano.ciacola.document.render.DocumentRenderConfig
import dev.sebastiano.ciacola.document.render.buildDocumentSpeakerNotes
import dev.sebastiano.ciacola.document.render.buildDocumentStoryboard
import dev.sebastiano.ciacola.presenter.StoryboardSpeakerNotesRegistry
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemes
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

@OptIn(ExperimentalComposeUiApi::class)
class FoundationDocumentMigrationTest {

    private fun loadFoundationDocument(): DeckDocument {
        val reg = AllDocumentDecks.foundation
        val raw = javaClass.classLoader.getResourceAsStream(reg.resourcePath)!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val decoded = DeckDocumentCodec.decode(
            rawText = raw,
            specimens = reg.specimens,
            effects = BuildEffectRegistry.Standard,
        ) as DeckDecodeResult.Success
        assertTrue(decoded.lintIssues.isEmpty(), "Expected 0 lint issues on foundation.deck.json: ${decoded.lintIssues}")
        return decoded.document
    }

    @Test
    fun `foundation deck json renders all 5 slides and 14 beats at 1920x1080 in dark and light`() {
        val doc = loadFoundationDocument()
        val outDir = System.getProperty("ciacola.foundationPreview.dir")?.let(::File)
        outDir?.mkdirs()
        val screenshotsDir = (System.getProperty("ciacola.screenshots.dir") ?: System.getenv("CIACOLA_SCREENSHOTS_DIR"))
            ?.let(::File)
        screenshotsDir?.mkdirs()

        assertEquals(listOf(3, 2, 1, 3, 5), doc.slides.map { it.beats.size })

        for ((preset, themeKey) in listOf(DeckThemePreset.StageDark to "dark", DeckThemePreset.KeynoteLight to "light")) {
            val theme = DeckThemes.forPreset(preset)
            val expectedBg = theme.colors.background
            val expectedAccent = theme.colors.accent
            val expectedCodeBg = theme.colors.code.background
            val docSession = RenderSession.forStill(doc)
            val docStoryboard = buildDocumentStoryboard(
                session = docSession,
                config = DocumentRenderConfig(
                    themeName = themeKey,
                    specimens = AllDocumentDecks.foundation.specimens,
                ),
            )

            assertEquals(IntSize(1920, 1080), docStoryboard.format.size)
            assertEquals(5, docStoryboard.scenes.size)
            assertEquals(listOf(3, 2, 1, 3, 5), docStoryboard.scenes.map { it.frames.size })

            val docNotes = assertNotNull(StoryboardSpeakerNotesRegistry.forStoryboard(docStoryboard))
            assertEquals(5, docNotes.scenes.size)
            for (sIdx in 0 until 5) {
                val dScene = assertNotNull(docNotes.scenes[sIdx])
                assertTrue(dScene.title.isNotBlank(), "Scene title at $sIdx must not be blank")
                for (bIdx in doc.slides[sIdx].beats.indices) {
                    val dBeat = dScene.beatNotes[bIdx].orEmpty()
                    assertTrue(dBeat.isNotBlank(), "Beat note at s$sIdx:b$bIdx must not be blank")
                }
            }

            // Render every resting beat at 1920x1080 (Density 2f) and retain frames for spatial layout assertions
            val renderedBeats = Array(doc.slides.size) { sIdx ->
                Array(doc.slides[sIdx].beats.size) { ByteArray(0) }
            }

            for (sIdx in doc.slides.indices) {
                for (bIdx in doc.slides[sIdx].beats.indices) {
                    val index = Storyboard.Index(sIdx, bIdx)
                    val rgba = renderStoryboardBeat(docStoryboard, index)
                    renderedBeats[sIdx][bIdx] = rgba
                    if (outDir != null) {
                        savePng(rgba, 1920, 1080, File(outDir, "${themeKey}_s${sIdx + 1}_b${bIdx}_doc.png"))
                    }
                    if (screenshotsDir != null && sIdx == 3 && bIdx == 2) {
                        val name = if (themeKey == "dark") "slide4-islands-dark.png" else "slide4-intellij-light.png"
                        savePng(rgba, 1920, 1080, File(screenshotsDir, name))
                    }

                    // Slide 3 (index 2) fills stage with ambient vertex mesh; other slides match theme canvas at corners
                    if (sIdx != 2) {
                        assertPixelNearColor(
                            rgba = rgba,
                            x = 8,
                            y = 8,
                            expected = expectedBg,
                            tolerance = 4,
                            label = "$themeKey s${sIdx + 1}:b$bIdx top-left canvas corner",
                        )
                    }

                    // Every slide renders its eyebrow band at y in 128..172
                    val eyebrowNonBg = countPixelsDifferingFrom(
                        rgba = rgba,
                        xRange = 128..1792,
                        yRange = 128..172,
                        ref = pixelRgb(rgba, 128, 128),
                        threshold = 18,
                    )
                    assertTrue(
                        eyebrowNonBg > 200,
                        "$themeKey s${sIdx + 1}:b$bIdx missing rendered eyebrow band (got $eyebrowNonBg px)",
                    )
                }
            }

            // --- Slide 1 (beats): prose morphs across b0 -> b1 -> b2; bottom-right shared badge enters only on b2 ---
            assertTrue(
                regionDiffFraction(renderedBeats[0][0], renderedBeats[0][1], 128..1792, 240..520) > 0.005,
                "$themeKey s1 prose must change visually from b0 to b1",
            )
            assertTrue(
                regionDiffFraction(renderedBeats[0][1], renderedBeats[0][2], 128..1792, 240..520) > 0.005,
                "$themeKey s1 prose must change visually from b1 to b2",
            )
            assertPixelNearColor(renderedBeats[0][0], 1764, 924, expectedBg, 4, "$themeKey s1:b0 badge hidden")
            assertPixelNearColor(renderedBeats[0][1], 1764, 924, expectedBg, 4, "$themeKey s1:b1 badge hidden")
            assertPixelNearColor(renderedBeats[0][2], 1764, 924, expectedAccent, 10, "$themeKey s1:b2 badge visible")

            // --- Slide 2 (shared-move): card starts top-left (128..608, 240..720) on b0, expands center (624..1296, 260..932) on b1 ---
            assertPixelNearColor(renderedBeats[1][0], 220, 300, expectedAccent, 10, "$themeKey s2:b0 shared card top-left")
            assertPixelNearColor(renderedBeats[1][0], 1180, 340, expectedBg, 4, "$themeKey s2:b0 expanded slot empty")
            assertPixelNearColor(renderedBeats[1][1], 220, 300, expectedBg, 4, "$themeKey s2:b1 top-left slot vacated")
            assertPixelNearColor(renderedBeats[1][1], 1180, 340, expectedAccent, 10, "$themeKey s2:b1 expanded card centered")

            // --- Slide 3 (ambient-loop): multi-color mesh spans all quadrants + center scrim card has foreground text ---
            val meshCorners = listOf(
                pixelRgb(renderedBeats[2][0], 16, 16),
                pixelRgb(renderedBeats[2][0], 960, 16),
                pixelRgb(renderedBeats[2][0], 16, 540),
                pixelRgb(renderedBeats[2][0], 1904, 540),
            )
            var distinctCornerPairs = 0
            for (i in meshCorners.indices) {
                for (j in i + 1 until meshCorners.size) {
                    val (r1, g1, b1) = meshCorners[i]
                    val (r2, g2, b2) = meshCorners[j]
                    if (maxOf(abs(r1 - r2), abs(g1 - g2), abs(b1 - b2)) > 12) distinctCornerPairs++
                }
            }
            assertTrue(
                distinctCornerPairs >= 4,
                "$themeKey s3 ambient mesh should produce distinct gradient quadrants, got pairs=$distinctCornerPairs ($meshCorners)",
            )
            val cardTextPixels = countPixelsDifferingFrom(
                rgba = renderedBeats[2][0],
                xRange = 420..1500,
                yRange = 480..720,
                ref = pixelRgb(renderedBeats[2][0], 390, 450),
                threshold = 24,
            )
            assertTrue(
                cardTextPixels > 2_000,
                "$themeKey s3 scrim card must render foreground headline & subhead (got $cardTextPixels px)",
            )

            // --- Slide 4 (highlighted-code): code surface matches codeBackground and evolves across b0 -> b1 -> b2 ---
            assertPixelNearColor(renderedBeats[3][0], 170, 440, expectedCodeBg, 10, "$themeKey s4:b0 code card fill")
            assertTrue(
                regionDiffFraction(renderedBeats[3][0], renderedBeats[3][1], 128..1792, 408..788) > 0.002,
                "$themeKey s4 code card must change visibly from b0 to b1",
            )
            assertTrue(
                regionDiffFraction(renderedBeats[3][1], renderedBeats[3][2], 128..1792, 408..788) > 0.002,
                "$themeKey s4 code card must change visibly from b1 to b2",
            )

            // --- Slide 5 (keynote-effects-lab): every beat transition b0->b1->b2->b3->b4 progresses visibly ---
            for (b in 0 until 4) {
                val delta = regionDiffFraction(renderedBeats[4][b], renderedBeats[4][b + 1], 0..1919, 180..1040)
                assertTrue(
                    delta > 0.002,
                    "$themeKey s5 beat $b -> ${b + 1} must advance visually (diffFraction=$delta)",
                )
            }
            // IMMUTABLE stamp enters at beat 2 in stage rect (270..1030, 740..896)
            val stampBefore = countPixelsDifferingFrom(renderedBeats[4][1], 270..1030, 740..896, pixelRgb(renderedBeats[4][1], 8, 8), 20)
            val stampAfter = countPixelsDifferingFrom(renderedBeats[4][2], 270..1030, 740..896, pixelRgb(renderedBeats[4][2], 8, 8), 20)
            assertTrue(
                stampBefore < 50 && stampAfter > 2_000,
                "$themeKey s5 IMMUTABLE stamp must enter at b2 (before=$stampBefore, after=$stampAfter)",
            )
        }
    }

    @Test
    fun `foundation ambient mesh obeys document loopPeriodsMs overrides cleanly`() {
        val baseDoc = loadFoundationDocument()
        val fastDoc = baseDoc.copy(loopPeriodsMs = mapOf("$FOUNDATION_AMBIENT_MESH_REF/time" to 3_000L))
        val slowDoc = baseDoc.copy(loopPeriodsMs = mapOf("$FOUNDATION_AMBIENT_MESH_REF/time" to 12_000L))

        val fastSession = RenderSession.forPresent(documentProvider = { fastDoc }, subCompositionScrubMillis = { 750L }) // quarter period of 3000ms -> sin(pi/2)=1
        val slowSession = RenderSession.forPresent(documentProvider = { slowDoc }, subCompositionScrubMillis = { 3_000L }) // quarter period of 12000ms -> sin(pi/2)=1
        val zeroSession = RenderSession.forPresent(documentProvider = { fastDoc }, subCompositionScrubMillis = { 0L })

        val fastSb = buildDocumentStoryboard(fastSession, DocumentRenderConfig(themeName = "dark", specimens = AllDocumentDecks.foundation.specimens))
        val slowSb = buildDocumentStoryboard(slowSession, DocumentRenderConfig(themeName = "dark", specimens = AllDocumentDecks.foundation.specimens))
        val zeroSb = buildDocumentStoryboard(zeroSession, DocumentRenderConfig(themeName = "dark", specimens = AllDocumentDecks.foundation.specimens))

        val fastFrame = renderStoryboardBeat(fastSb, Storyboard.Index(2, 0))
        val slowFrame = renderStoryboardBeat(slowSb, Storyboard.Index(2, 0))
        val zeroFrame = renderStoryboardBeat(zeroSb, Storyboard.Index(2, 0))

        // At identical phase (quarter period), fastDoc@750ms and slowDoc@3000ms must match identically!
        assertTrue(
            regionDiffFraction(fastFrame, slowFrame, 0..1919, 0..1079) < 0.0001,
            "foundation.ambientMesh should derive phase from loopPeriodsMs so matching phase produces identical frames",
        )
        // And quarter-period phase must differ visibly from t=0!
        assertTrue(
            regionDiffFraction(zeroFrame, fastFrame, 0..1919, 0..1079) > 0.01,
            "foundation.ambientMesh at quarter-period must differ visibly from t=0",
        )
    }

    private fun pixelRgb(rgba: ByteArray, x: Int, y: Int): Triple<Int, Int, Int> {
        val idx = (y * 1920 + x) * 4
        return Triple(
            rgba[idx].toInt() and 0xFF,
            rgba[idx + 1].toInt() and 0xFF,
            rgba[idx + 2].toInt() and 0xFF,
        )
    }

    private fun assertPixelNearColor(
        rgba: ByteArray,
        x: Int,
        y: Int,
        expected: Color,
        tolerance: Int,
        label: String,
    ) {
        val (r, g, b) = pixelRgb(rgba, x, y)
        val er = (expected.red * 255f).toInt()
        val eg = (expected.green * 255f).toInt()
        val eb = (expected.blue * 255f).toInt()
        assertTrue(
            maxOf(abs(r - er), abs(g - eg), abs(b - eb)) <= tolerance,
            "$label: pixel at ($x,$y) was ($r,$g,$b), expected ($er,$eg,$eb) ±$tolerance",
        )
    }

    private fun countPixelsDifferingFrom(
        rgba: ByteArray,
        xRange: IntRange,
        yRange: IntRange,
        ref: Triple<Int, Int, Int>,
        threshold: Int,
    ): Int {
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                val (r, g, b) = pixelRgb(rgba, x, y)
                if (maxOf(abs(r - ref.first), abs(g - ref.second), abs(b - ref.third)) > threshold) {
                    count++
                }
            }
        }
        return count
    }

    private fun regionDiffFraction(
        a: ByteArray,
        b: ByteArray,
        xRange: IntRange,
        yRange: IntRange,
        threshold: Int = 10,
    ): Double {
        var total = 0
        var diff = 0
        for (y in yRange) {
            for (x in xRange) {
                total++
                val idx = (y * 1920 + x) * 4
                val dr = abs((a[idx].toInt() and 0xFF) - (b[idx].toInt() and 0xFF))
                val dg = abs((a[idx + 1].toInt() and 0xFF) - (b[idx + 1].toInt() and 0xFF))
                val db = abs((a[idx + 2].toInt() and 0xFF) - (b[idx + 2].toInt() and 0xFF))
                if (maxOf(dr, dg, db) > threshold) diff++
            }
        }
        return if (total == 0) 0.0 else diff.toDouble() / total.toDouble()
    }

    @Test
    fun `foundation deck json speaker notes and workspace legacy overrides archival`() {
        val doc = loadFoundationDocument()
        assertEquals(5, doc.slides.size)
        assertEquals(14, doc.slides.sumOf { it.beats.size })

        // Check workspace .storyboard/ for any legacy override files and archive them if present
        val workspaceRoot = resolveStoryboardWorkspaceRoot()
        val storyboardDir = workspaceRoot.resolve(".storyboard")
        if (Files.isDirectory(storyboardDir)) {
            val elemFile = storyboardDir.resolve("element-overrides.json").takeIf { Files.isRegularFile(it) }
            val timeFile = storyboardDir.resolve("timeline-overrides.json").takeIf { Files.isRegularFile(it) }
            val dsFile = storyboardDir.resolve("design-system.json").takeIf { Files.isRegularFile(it) }
            if (elemFile != null || timeFile != null || dsFile != null) {
                val archiveDir = storyboardDir.resolve("archived-legacy")
                val report = LegacyOverridesImporter.importFromFiles(
                    document = doc,
                    elementOverridesFile = elemFile,
                    timelineOverridesFile = timeFile,
                    designSystemOverridesByTheme = if (dsFile != null) mapOf("dark" to dsFile) else emptyMap(),
                    archiveDir = archiveDir,
                )
                assertTrue(report.archivedFiles.all { Files.exists(it) })
            }
        }

        // Also test synthetic legacy override import + archival against foundation.deck.json
        val tmpDir = Files.createTempDirectory("foundation-legacy-import")
        try {
            val elemPath = tmpDir.resolve("element-overrides.json")
            Files.writeString(
                elemPath,
                """{"elements":{"s1:prose":{"elementId":"s1:prose","text":"Imported baseline state."},"s1:prose:b1":{"elementId":"s1:prose:b1","text":"Imported beat 1 state."}}}""",
                StandardCharsets.UTF_8,
            )
            val timePath = tmpDir.resolve("timeline-overrides.json")
            Files.writeString(
                timePath,
                """{"tracks":{"s1:prose":{"trackId":"s1:prose","durationMs":620}},"loopPeriodsMs":{"foundation.ambientMesh/time":7500}}""",
                StandardCharsets.UTF_8,
            )
            val archivePath = tmpDir.resolve("archived-legacy")
            val report = LegacyOverridesImporter.importFromFiles(
                document = doc,
                elementOverridesFile = elemPath,
                timelineOverridesFile = timePath,
                archiveDir = archivePath,
            )
            assertEquals(2, report.archivedFiles.size)
            assertFalse(Files.exists(elemPath))
            assertFalse(Files.exists(timePath))
            assertTrue(report.archivedFiles.all { Files.exists(it) })
            assertEquals(7500L, report.document.loopPeriodsMs["foundation.ambientMesh/time"])
            val s1Prose = report.document.slides[0].nodes.first { it.id == "s1:prose" }
            assertEquals("Imported baseline state.", s1Prose.text?.value?.plainText)
            assertEquals("Imported beat 1 state.", JsonMergePatch.resolveNodeAtBeat(s1Prose, 1).text?.value?.plainText)
            val canonical = CanonicalJson.print(report.document)
            assertTrue(DeckDocumentCodec.decode(canonical, AllDocumentDecks.foundation.specimens) is DeckDecodeResult.Success)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }

        val notes = buildDocumentSpeakerNotes(doc)
        assertEquals(5, notes.scenes.size)
        for ((sIdx, slide) in doc.slides.withIndex()) {
            val sceneNotes = assertNotNull(notes.scenes[sIdx])
            assertEquals(slide.beats.size, sceneNotes.beatNotes.size)
        }
    }

    @Test
    fun `variable font wght selection and Noto Emoji weight clamping`() {
        val fontsDir = listOf(
            File("deck/src/desktopMain/resources/fonts"),
            File("src/desktopMain/resources/fonts"),
        ).first { it.isDirectory }
        val resolver = DeckFontResolver(
            declarations = BundledFontCatalog.AllBundled,
            resourceLoader = ClasspathFontResourceLoader(fallbackDirectories = listOf(fontsDir)),
            systemFontLookup = SystemFontLookup { null },
        )

        lateinit var composeResolver: FontFamily.Resolver
        val probe = ImageComposeScene(width = 16, height = 16, density = Density(2f)) {
            composeResolver = LocalFontFamilyResolver.current
        }
        try {
            probe.render()
        } finally {
            probe.close()
        }

        val hanken = assertNotNull(resolver.resolveFamily("Hanken Grotesk"))
        for (w in listOf(200, 400, 600, 800)) {
            val res = composeResolver.resolve(hanken.fontFamily, FontWeight(w), FontStyle.Normal).value as FontLoadResult
            val tf = assertNotNull(res.typeface)
            assertEquals(w.toFloat(), tf.variations?.firstOrNull { it.tag == "wght" }?.value)
        }

        val notoEmoji = assertNotNull(resolver.resolveFamily("Noto Emoji"))
        assertEquals(FontWeight(700), resolver.clampWeight("Noto Emoji", FontWeight(900)))
        assertEquals(FontWeight(300), resolver.clampWeight("Noto Emoji", FontWeight(100)))
        val clampedStyle = resolver.resolveTextStyle(
            familyName = "Noto Emoji",
            style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight(900), color = Color.White),
        )
        assertEquals(FontWeight(700), clampedStyle.fontWeight)
        val emojiRes = composeResolver.resolve(notoEmoji.fontFamily, clampedStyle.fontWeight!!, FontStyle.Normal).value as FontLoadResult
        assertEquals(700f, emojiRes.typeface?.variations?.firstOrNull { it.tag == "wght" }?.value)
    }

    private fun renderStoryboardBeat(
        storyboard: Storyboard,
        targetIndex: Storyboard.Index,
    ): ByteArray {
        lateinit var animaticRef: dev.bnorm.storyboard.easel.Animatic
        val scene = ImageComposeScene(width = 1920, height = 1080, density = Density(2f)) {
            val animatic = rememberAnimatic(initialIndex = targetIndex, storyboard = { storyboard })
            animaticRef = animatic
            Box(Modifier.fillMaxSize()) {
                Easel(animatic = animatic, mode = SceneMode.Preview)
            }
        }
        try {
            scene.render(0L)
            runBlocking { animaticRef.jumpTo(targetIndex) }
            scene.render(16_000_000L)
            scene.render(32_000_000L)
            val skiaImg = scene.render(48_000_000L)
            val bmp = Bitmap()
            bmp.allocPixels(ImageInfo(1920, 1080, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
            assertTrue(skiaImg.readPixels(bmp, 0, 0))
            return bmp.readPixels(bmp.imageInfo, 1920 * 4, 0, 0)!!
        } finally {
            scene.close()
        }
    }

    private fun savePng(rgba: ByteArray, width: Int, height: Int, outFile: File) {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val img = SkiaImage.makeRaster(info, rgba, width * 4)
        val png = img.encodeToData(EncodedImageFormat.PNG) ?: return
        outFile.writeBytes(png.bytes)
    }
}
