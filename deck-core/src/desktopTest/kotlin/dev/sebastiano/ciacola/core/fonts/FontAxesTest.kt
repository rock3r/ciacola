package dev.sebastiano.ciacola.core.fonts

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontAxesTest {

    private val density = Density(1f)

    private val measurer: TextMeasurer by lazy {
        val scene = ImageComposeScene(width = 1, height = 1, density = density) {}
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    private fun bundledFontsDir(): File =
        listOf(
            File("deck/src/desktopMain/resources/fonts"),
            File("../deck/src/desktopMain/resources/fonts"),
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate deck/src/desktopMain/resources/fonts from ${File(".").absolutePath}")

    private fun resolver(declarations: List<DeckFontDeclaration>): DeckFontResolver = DeckFontResolver(
        declarations = declarations,
        resourceLoader = ClasspathFontResourceLoader(fallbackDirectories = listOf(bundledFontsDir())),
        systemFontLookup = SystemFontLookup { null },
    )

    private fun composeFontResolver(): FontFamily.Resolver {
        lateinit var resolver: FontFamily.Resolver
        val scene = ImageComposeScene(width = 16, height = 16, density = density) {
            resolver = LocalFontFamilyResolver.current
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return resolver
    }

    @Test
    fun `axis tags must be four printable ASCII characters with trailing-only padding`() {
        for (valid in listOf("wght", "wdth", "GRAD", "opsz", "XOPQ", "ab  ", "a1_-")) {
            assertTrue(FontAxes.isValidTag(valid), "'$valid' should be valid")
        }
        for (invalid in listOf("", "wgh", "wghtt", " wgh", "a b ", "wghé", "wg\th")) {
            assertFalse(FontAxes.isValidTag(invalid), "'$invalid' should be invalid")
        }
    }

    @Test
    fun `resolve clamps to fvar ranges, ignores unsupported tags, and sorts by tag`() {
        val available = listOf(
            VariationAxisRange(tag = "wght", minValue = 300f, defaultValue = 400f, maxValue = 700f),
            VariationAxisRange(tag = "wdth", minValue = 75f, defaultValue = 100f, maxValue = 125f),
        )
        val resolution = FontAxes.resolve(
            requested = mapOf("wdth" to 200.0, "GRAD" to 50.0, "wght" to 500.0, "bad!!" to 1.0, "slnt" to Double.NaN),
            available = available,
        )
        assertEquals(listOf("wdth", "wght"), resolution.applied.keys.toList())
        assertEquals(125f, resolution.applied["wdth"])
        assertEquals(500f, resolution.applied["wght"])
        assertEquals(setOf("GRAD"), resolution.unsupported)
        assertEquals(setOf("bad!!", "slnt"), resolution.invalid)
        val clamped = resolution.clamped.single()
        assertEquals(ClampedAxis(tag = "wdth", requested = 200.0, applied = 125.0, min = 75.0, max = 125.0), clamped)

        assertEquals(AxisResolution.Empty, FontAxes.resolve(emptyMap(), available))
        assertEquals(mapOf("wdth" to 80.0, "GRAD" to 1.0), FontAxes.merge(mapOf("wdth" to 100.0, "GRAD" to 1.0), mapOf("wdth" to 80.0)))
        assertEquals("GRAD=1.0,wdth=80.0", FontAxes.cacheKey(mapOf("wdth" to 80f, "GRAD" to 1f)))
    }

    @Test
    fun `static axes derive a memoized family whose Skia typefaces carry the clamped coordinates`() {
        val resolver = resolver(listOf(BundledFontCatalog.Fredoka))
        val fredoka = assertNotNull(resolver.resolveFamily("Fredoka"))

        val narrow = resolver.withAxes(fredoka, mapOf("wdth" to 75.0))
        assertNotEquals(fredoka.fontFamily, narrow.fontFamily)
        assertSame(narrow, resolver.withAxes(fredoka, mapOf("wdth" to 75.0)), "Equal requests must share one family")
        assertSame(narrow, resolver.findResolvedFamily(narrow.fontFamily), "Derived families must stay resolver-managed")
        assertSame(narrow, resolver.withAxes(narrow, mapOf("wdth" to 75.0)), "Re-deriving from a derived family starts from the base")

        // Unsupported tags alone leave the family untouched.
        assertSame(fredoka, resolver.withAxes(fredoka, mapOf("GRAD" to 100.0)))
        assertSame(fredoka, resolver.withAxes(fredoka, emptyMap()))

        // Out-of-range values clamp to the axis bounds.
        val clamped = resolver.withAxes(fredoka, mapOf("wdth" to 10.0))
        assertTrue(clamped.faces.all { it.axisCoordinates == mapOf("wdth" to 75f) })

        val composeResolver = composeFontResolver()
        for (w in listOf(300, 500, 700)) {
            val result = composeResolver.resolve(narrow.fontFamily, FontWeight(w), FontStyle.Normal).value as FontLoadResult
            val variations = assertNotNull(result.typeface).variations.orEmpty().associate { it.tag to it.value }
            assertEquals(75f, variations["wdth"], "Compose typeface for w=$w must carry wdth=75")
            assertEquals(w.toFloat(), variations["wght"], "Compose typeface for w=$w must keep its wght instance")

            val face = assertNotNull(narrow.resolveFace(FontWeight(w), FontStyle.Normal))
            val rendering = assertNotNull(resolver.renderingTypeface(face))
            val renderingVariations = rendering.variations.orEmpty().associate { it.tag to it.value }
            assertEquals(75f, renderingVariations["wdth"], "Certification typeface for w=$w must carry wdth=75")
            assertEquals(w.toFloat(), renderingVariations["wght"])
        }
    }

    @Test
    fun `wdth changes measured advances while clamped requests measure like the bound`() {
        val resolver = resolver(listOf(BundledFontCatalog.Fredoka))
        val base = TextStyle(fontSize = 48.sp, fontWeight = FontWeight(400))
        fun widthOf(axes: Map<String, Double>): Int {
            val style = resolver.resolveTextStyle("Fredoka", base, axes = axes)
            return measurer.measure(AnnotatedString("Variable width typography"), style, softWrap = false).size.width
        }
        val narrow = widthOf(mapOf("wdth" to 75.0))
        val default = widthOf(emptyMap())
        val wide = widthOf(mapOf("wdth" to 125.0))
        assertTrue(narrow < default, "wdth=75 ($narrow) must be narrower than the default instance ($default)")
        assertTrue(default < wide, "wdth=125 ($wide) must be wider than the default instance ($default)")
        assertEquals(narrow, widthOf(mapOf("wdth" to -500.0)), "Below-range values must clamp to wdth=75")
        assertEquals(wide, widthOf(mapOf("wdth" to 9000.0)), "Above-range values must clamp to wdth=125")
    }

    @Test
    fun `span axes apply on top of the paragraph family`() {
        val resolver = resolver(listOf(BundledFontCatalog.Fredoka))
        val span = resolver.resolveSpanStyle(
            familyName = "Fredoka",
            style = SpanStyle(fontWeight = FontWeight(900)),
            axes = mapOf("wdth" to 125.0),
        )
        assertEquals(FontWeight(700), span.fontWeight, "Span weights still clamp to the family range")
        val managed = assertNotNull(resolver.findResolvedFamily(span.fontFamily))
        assertTrue(managed.faces.all { it.axisCoordinates == mapOf("wdth" to 125f) })

        val rebound = resolver.applyAxes(span, mapOf("wdth" to 80.0))
        val reboundFamily = assertNotNull(resolver.findResolvedFamily(rebound.fontFamily))
        assertTrue(reboundFamily.faces.all { it.axisCoordinates == mapOf("wdth" to 80f) })
    }

    @Test
    fun `GRAD changes ink without reflowing lines or advances`() {
        val resolver = resolver(BundledFontCatalog.GoogleSans)
        val googleSans = assertNotNull(resolver.resolveFamily("Google Sans"))
        val grad = googleSans.faces.first().inspection!!.variationAxes.first { it.tag == "GRAD" }
        val text = AnnotatedString(
            "Grade lets dark and light themes share one optical weight without any line wrap reflow at all.",
        )
        fun layout(gradValue: Double) = measurer.measure(
            text = text,
            style = resolver.resolveTextStyle(
                familyName = "Google Sans",
                style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight(400)),
                axes = mapOf("GRAD" to gradValue, "opsz" to 24.0),
            ),
            constraints = Constraints(maxWidth = 900),
        )
        val dark = layout(grad.minValue.toDouble())
        val light = layout(grad.maxValue.toDouble())
        assertEquals(dark.lineCount, light.lineCount, "GRAD must not change line count")
        assertTrue(dark.lineCount > 1, "Fixture must wrap to exercise reflow")
        for (line in 0 until dark.lineCount) {
            assertEquals(dark.getLineEnd(line), light.getLineEnd(line), "GRAD must not move line $line break")
            assertEquals(dark.getLineRight(line), light.getLineRight(line), "GRAD must not change line $line advance")
        }
        assertEquals(dark.size, light.size)

        val darkFace = assertNotNull(resolver.findResolvedFamily(dark.layoutInput.style.fontFamily)).faces.first()
        val lightFace = assertNotNull(resolver.findResolvedFamily(light.layoutInput.style.fontFamily)).faces.first()
        assertEquals(grad.minValue, darkFace.axisCoordinates["GRAD"])
        assertEquals(grad.maxValue, lightFace.axisCoordinates["GRAD"])
    }

    @Test
    fun `resolveAxes reports unsupported and clamped tags for lint`() {
        val resolver = resolver(listOf(BundledFontCatalog.Fredoka, BundledFontCatalog.NanumPenScript))
        val fredoka = assertNotNull(resolver.resolveAxes("Fredoka", mapOf("wdth" to 150.0, "GRAD" to 0.0)))
        assertEquals(setOf("GRAD"), fredoka.unsupported)
        assertEquals(listOf("wdth"), fredoka.clamped.map { it.tag })

        val static = assertNotNull(resolver.resolveAxes("Nanum Pen Script", mapOf("wdth" to 80.0)))
        assertEquals(setOf("wdth"), static.unsupported)
        assertTrue(static.applied.isEmpty())
    }
}
