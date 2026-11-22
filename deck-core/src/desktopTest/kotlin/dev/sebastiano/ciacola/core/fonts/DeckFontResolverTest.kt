package dev.sebastiano.ciacola.core.fonts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

class DeckFontResolverTest {

    private val testDensity = Density(2f)
    private val testMeasurer: TextMeasurer by lazy {
        val scene = ImageComposeScene(width = 1, height = 1, density = Density(1f)) {}
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = testDensity,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    private fun measureAndCertifyParagraph(
        resolver: DeckFontResolver,
        paragraph: AnnotatedString,
        baseStyle: TextStyle,
        effectiveConfig: EffectiveFontFeatureConfig =
            EffectiveFontFeatureConfig.fromFeatureSettings(baseStyle.fontFeatureSettings),
        isLtrOverride: ((Int) -> Boolean)? = null,
        limits: GsubParserLimits = GsubParserLimits.Default,
    ): ParagraphCharacterSafetyCertifier {
        val assignment = resolver.assignParagraphFaces(paragraph, baseStyle)
        val ltrCheck: (Int) -> Boolean = isLtrOverride ?: run {
            val layout = runCatching {
                testMeasurer.measure(assignment.routedAnnotatedString, baseStyle)
            }.getOrNull()
            if (layout != null && paragraph.text.isNotEmpty()) {
                { offset: Int ->
                    val clamped = offset.coerceIn(0, paragraph.text.length - 1)
                    layout.getParagraphDirection(clamped) == ResolvedTextDirection.Ltr &&
                        layout.getBidiRunDirection(clamped) == ResolvedTextDirection.Ltr
                }
            } else {
                { false }
            }
        }
        return resolver.certifyParagraphCharacterSafety(
            paragraph = paragraph,
            baseStyle = baseStyle,
            effectiveConfig = effectiveConfig,
            isLayoutLtrAtOffset = ltrCheck,
            existingAssignment = assignment,
            limits = limits,
        )
    }

    private fun isSingleWordParagraphSafe(
        resolver: DeckFontResolver,
        word: String,
        familyName: String,
        weight: FontWeight = FontWeight.Normal,
        style: FontStyle = FontStyle.Normal,
    ): Boolean {
        val resolvedStyle = resolver.resolveTextStyle(
            familyName = familyName,
            style = TextStyle(fontWeight = weight, fontStyle = style),
            disableOptionalLigatures = true,
        )
        return measureAndCertifyParagraph(
            resolver = resolver,
            paragraph = AnnotatedString(word),
            baseStyle = resolvedStyle,
            effectiveConfig = EffectiveFontFeatureConfig.CharacterForcedOff,
        ).isWordRangeSafe(0, word.length)
    }

    private fun isSingleWordParagraphSafe(
        resolver: DeckFontResolver,
        word: AnnotatedString,
        baseStyle: TextStyle,
    ): Boolean = measureAndCertifyParagraph(
        resolver = resolver,
        paragraph = word,
        baseStyle = baseStyle,
    ).isWordRangeSafe(0, word.text.length)

    private fun bundledFontsDir(): File {
        val candidates = listOf(
            File("deck/src/desktopMain/resources/fonts"),
            File("../deck/src/desktopMain/resources/fonts"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate deck/src/desktopMain/resources/fonts from ${File(".").absolutePath}")
    }

    private fun realBundleResourceLoader(): FontResourceLoader =
        ClasspathFontResourceLoader(fallbackDirectories = listOf(bundledFontsDir()))

    @Test
    fun `variable fonts instantiate distinct Skia wght typefaces and clamp requested weights`() {
        val resolver = DeckFontResolver(
            declarations = BundledFontCatalog.AllBundled,
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )

        val errors = resolver.issues.filter { it.severity == FontValidationSeverity.Error }
        assertTrue(errors.isEmpty(), "Unexpected font validation errors: $errors")

        // Obtain the real Compose Desktop FontFamily.Resolver via ImageComposeScene so we test the
        // exact FontListFontFamily lookup + Skia Typeface variation instantiation path used at runtime.
        lateinit var composeResolver: FontFamily.Resolver
        val probeScene = ImageComposeScene(width = 16, height = 16, density = Density(1f)) {
            composeResolver = LocalFontFamilyResolver.current
        }
        try {
            probeScene.render()
        } finally {
            probeScene.close()
        }

        // 1. Hanken Grotesk: wght 100..900 upright + italic
        val hanken = assertNotNull(resolver.resolveFamily("Hanken Grotesk"))
        assertEquals(FontOrigin.Bundled, hanken.origin)
        assertEquals(100, hanken.minWeight)
        assertEquals(900, hanken.maxWeight)
        assertTrue(hanken.hasNormal && hanken.hasItalic)

        for (w in listOf(300, 400, 600, 700, 900)) {
            val normalFace = assertNotNull(hanken.resolveFace(FontWeight(w), FontStyle.Normal))
            assertEquals(w, normalFace.weight)
            val normalResult = composeResolver.resolve(hanken.fontFamily, FontWeight(w), FontStyle.Normal).value as FontLoadResult
            val tf = assertNotNull(normalResult.typeface)
            val wght = tf.variations?.firstOrNull { it.tag == "wght" }?.value
            assertEquals(w.toFloat(), wght, "Hanken Grotesk Normal w=$w must load exact wght axis")
            assertEquals(w, tf.fontStyle.weight.value)
            assertFalse(tf.isItalic)

            val italicFace = assertNotNull(hanken.resolveFace(FontWeight(w), FontStyle.Italic))
            assertEquals(w, italicFace.weight)
            val italicResult = composeResolver.resolve(hanken.fontFamily, FontWeight(w), FontStyle.Italic).value as FontLoadResult
            val itf = assertNotNull(italicResult.typeface)
            val iWght = itf.variations?.firstOrNull { it.tag == "wght" }?.value
            assertEquals(w.toFloat(), iWght, "Hanken Grotesk Italic w=$w must load exact wght axis")
            assertEquals(w, itf.fontStyle.weight.value)
            assertTrue(itf.isItalic)
        }

        // 2. Noto Emoji: wght 300..700 -> requested 900 MUST clamp to 700, requested 100 MUST clamp to 300!
        val notoEmoji = assertNotNull(resolver.resolveFamily("Noto Emoji"))
        assertEquals(300, notoEmoji.minWeight)
        assertEquals(700, notoEmoji.maxWeight)
        assertEquals(setOf(300, 400, 500, 600, 700), notoEmoji.availableWeights)
        assertEquals(FontWeight(700), resolver.clampWeight("Noto Emoji", FontWeight(900)))
        assertEquals(FontWeight(300), resolver.clampWeight("Noto Emoji", FontWeight(100)))

        val clampedTextStyle = resolver.resolveTextStyle(
            familyName = "Noto Emoji",
            style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight(900)),
        )
        assertEquals(FontWeight(700), clampedTextStyle.fontWeight)
        assertEquals(notoEmoji.fontFamily, clampedTextStyle.fontFamily)

        val clampedSpanStyle = resolver.resolveSpanStyle(
            familyName = "Noto Emoji",
            style = SpanStyle(fontWeight = FontWeight(900)),
        )
        assertEquals(FontWeight(700), clampedSpanStyle.fontWeight)

        val emojiFace700 = assertNotNull(notoEmoji.resolveFace(FontWeight(900), FontStyle.Normal))
        assertEquals(700, emojiFace700.weight)
        val emojiResult = composeResolver.resolve(
            notoEmoji.fontFamily,
            clampedTextStyle.fontWeight!!,
            FontStyle.Normal,
        ).value as FontLoadResult
        val emojiTf = assertNotNull(emojiResult.typeface)
        assertEquals(700f, emojiTf.variations?.firstOrNull { it.tag == "wght" }?.value)
        assertEquals(700, emojiTf.fontStyle.weight.value)

        // 3. Fredoka (300..700), Assistant (200..800), Nanum Pen Script (static 400)
        assertEquals(FontWeight(700), resolver.clampWeight("Fredoka", FontWeight(900)))
        assertEquals(FontWeight(300), resolver.clampWeight("Fredoka", FontWeight(100)))
        assertEquals(FontWeight(800), resolver.clampWeight("Assistant", FontWeight(900)))
        assertEquals(FontWeight(200), resolver.clampWeight("Assistant", FontWeight(100)))
        assertEquals(FontWeight(400), resolver.clampWeight("Nanum Pen Script", FontWeight(700)))
        assertEquals(FontWeight(400), resolver.clampWeight("Nanum Pen Script", FontWeight(100)))
    }

    @Test
    fun `clamped Noto Emoji at requested 900 renders pixel-identically to 700 with zero synthetic bold`() {
        val resolver = DeckFontResolver(
            declarations = listOf(BundledFontCatalog.NotoEmoji),
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )

        val styleFrom900 = resolver.resolveTextStyle(
            familyName = "Noto Emoji",
            style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight(900), color = Color.White),
        )
        val styleFrom700 = resolver.resolveTextStyle(
            familyName = "Noto Emoji",
            style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight(700), color = Color.White),
        )
        assertEquals(FontWeight(700), styleFrom900.fontWeight)

        val pixels900 = renderTextPixels("❤ ★ 🔥", styleFrom900)
        val pixels700 = renderTextPixels("❤ ★ 🔥", styleFrom700)
        assertTrue(pixels900.contentEquals(pixels700), "Clamped w=900 must match w=700 pixel-for-pixel (no faux-bold)")

        // Sanity check: rendered pixels are not blank and differ from w=300.
        val styleFrom300 = resolver.resolveTextStyle(
            familyName = "Noto Emoji",
            style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight(300), color = Color.White),
        )
        val pixels300 = renderTextPixels("❤ ★ 🔥", styleFrom300)
        assertFalse(pixels700.contentEquals(pixels300), "w=700 must visibly differ from w=300 variable axis")
    }

    @Test
    fun `role emoji fallback chain and applyEmojiFallback clamp weights and preserve authored text`() {
        val resolver = DeckFontResolver(
            declarations = BundledFontCatalog.HankenGrotesk + BundledFontCatalog.NotoEmoji,
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )

        val emoji = assertNotNull(resolver.emojiFamily)
        assertEquals("Noto Emoji", emoji.familyName)

        val chain = resolver.resolveFallbackChain("Hanken Grotesk")
        assertEquals(listOf("Hanken Grotesk", "Noto Emoji"), chain.map { it.familyName })

        val latinFace = assertNotNull(resolver.resolveFaceForCodePoint("Hanken Grotesk", 'J'.code, FontWeight(900)))
        assertEquals("Hanken Grotesk", latinFace.familyName)
        assertEquals(900, latinFace.weight)

        val hammerFace = assertNotNull(resolver.resolveFaceForCodePoint("Hanken Grotesk", 0x1F528, FontWeight(900)))
        assertEquals("Noto Emoji", hammerFace.familyName)
        assertEquals(700, hammerFace.weight) // clamped from 900 to 700!

        val source = "Jewel 🔨 ⏩\uFE0F!"
        val styled = resolver.applyEmojiFallback(source, "Hanken Grotesk", baseWeight = FontWeight(900))
        assertEquals(source, styled.text, "Authored text including U+FE0F must be preserved verbatim")
        assertTrue(styled.spanStyles.isNotEmpty(), "Emoji runs should receive Noto Emoji fallback spans")
        assertTrue(styled.spanStyles.all { it.item.fontFamily == emoji.fontFamily && it.item.fontWeight == FontWeight(700) })
    }

    @Test
    fun `system fonts resolve on macOS as non-character-safe and absent system fonts fall back cleanly`() {
        val cleanRegularBytes = SyntheticTtfBuilder.buildTtf(os2WeightClass = 400)
        val declarations = listOf(
            DeckFontDeclaration(family = "SafeSans", src = "fonts/SafeSans-Regular.ttf", weight = 400),
            DeckFontDeclaration(family = "Georgia", system = true, fallback = "SafeSans"),
            DeckFontDeclaration(family = "NonExistentCommercialFontXYZ", system = true, fallback = "SafeSans"),
        )
        val resolver = DeckFontResolver(
            declarations = declarations,
            resourceLoader = FontResourceLoader { src ->
                if (src == "fonts/SafeSans-Regular.ttf") cleanRegularBytes else null
            },
            systemFontLookup = MacOsSystemFontLookup(),
        )

        val safeSans = assertNotNull(resolver.resolveFamily("SafeSans"))
        assertEquals(FontOrigin.Bundled, safeSans.origin)
        assertTrue(isSingleWordParagraphSafe(resolver, "Hello", "SafeSans", FontWeight.Normal))

        val georgia = assertNotNull(resolver.resolveFamily("Georgia"))
        // On macOS Georgia is installed in /System/Library/Fonts/Supplemental; on other OSes it falls back.
        assertTrue(georgia.origin == FontOrigin.System || georgia.origin == FontOrigin.FallbackToBundled)
        assertFalse(georgia.isCharacterSafeFamily)
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello", "Georgia", FontWeight.Normal))

        val missingCommercial = assertNotNull(resolver.resolveFamily("NonExistentCommercialFontXYZ"))
        assertEquals(FontOrigin.FallbackToBundled, missingCommercial.origin)
        assertEquals("SafeSans", missingCommercial.resolvedFamilyName)
        assertNotEquals(safeSans.fontFamily, missingCommercial.fontFamily, "Fallback family must not alias bundled identity")
        assertFalse(missingCommercial.isCharacterSafeFamily)
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello", "NonExistentCommercialFontXYZ", FontWeight.Normal))

        // Verify AnnotatedString lookup also treats the fallback FontFamily as non-character-safe!
        val missingStyle = resolver.resolveTextStyle("NonExistentCommercialFontXYZ", TextStyle(fontWeight = FontWeight.Normal), disableOptionalLigatures = true)
        assertFalse(isSingleWordParagraphSafe(resolver, AnnotatedString("Hello"), missingStyle))
    }

    @Test
    fun `fallback chain validation detects cycles, missing fallbacks, and non-bundled terminals`() {
        val cleanBytes = SyntheticTtfBuilder.buildTtf(os2WeightClass = 400)

        // Valid multi-hop acyclic chain ending in a bundled family.
        val validChain = listOf(
            DeckFontDeclaration(family = "BundledBase", src = "fonts/base.ttf", weight = 400),
            DeckFontDeclaration(family = "SysMid", system = true, fallback = "BundledBase"),
            DeckFontDeclaration(family = "SysTop", system = true, fallback = "SysMid"),
        )
        assertTrue(validateFontDeclarations(validChain).isEmpty())
        val validResolver = DeckFontResolver(
            declarations = validChain,
            resourceLoader = FontResourceLoader { cleanBytes },
            systemFontLookup = SystemFontLookup { null },
        )
        assertEquals("BundledBase", validResolver.resolveFamily("SysTop")?.resolvedFamilyName)
        assertEquals(FontOrigin.FallbackToBundled, validResolver.resolveFamily("SysTop")?.origin)

        // Cyclic chain A -> B -> A must report FONT_FALLBACK_CYCLE and never hang.
        val cyclic = listOf(
            DeckFontDeclaration(family = "SysA", system = true, fallback = "SysB"),
            DeckFontDeclaration(family = "SysB", system = true, fallback = "SysA"),
        )
        val cyclicIssues = validateFontDeclarations(cyclic)
        assertTrue(cyclicIssues.any { it.code == "FONT_FALLBACK_CYCLE" })
        val cyclicResolver = DeckFontResolver(
            declarations = cyclic,
            resourceLoader = FontResourceLoader { null },
            systemFontLookup = SystemFontLookup { null },
        )
        assertEquals(FontOrigin.DefaultFallback, cyclicResolver.resolveFamily("SysA")?.origin)

        // Non-bundled terminal must report FONT_FALLBACK_NOT_BUNDLED.
        val dangling = listOf(
            DeckFontDeclaration(family = "Oscine", system = true, fallback = "UnknownFamily"),
        )
        val danglingIssues = validateFontDeclarations(dangling)
        assertTrue(danglingIssues.any { it.code == "FONT_FALLBACK_NOT_BUNDLED" })
    }

    @Test
    fun `per-word character safety checks mixed certified faces, uncovered glyphs, and uncertified faces`() {
        val safeRegularBytes = SyntheticTtfBuilder.buildTtf(
            bmpCodePoints = ('A'..'Z').map { it.code }.toSet() + ('a'..'z').map { it.code }.toSet(),
            os2WeightClass = 400,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(4)),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(0))),
            ),
        )
        val safeBoldBytes = SyntheticTtfBuilder.buildTtf(
            bmpCodePoints = ('A'..'Z').map { it.code }.toSet() + ('a'..'z').map { it.code }.toSet(),
            os2WeightClass = 700,
        )
        val unsafeRequiredLigBytes = SyntheticTtfBuilder.buildTtf(
            bmpCodePoints = ('A'..'Z').map { it.code }.toSet() + ('a'..'z').map { it.code }.toSet(),
            os2WeightClass = 400,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(4)),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
            ),
        )
        val corruptedBytes = ByteArray(20) { 0x11 }

        val declarations = listOf(
            DeckFontDeclaration(family = "SafeFamily", src = "fonts/safe-reg.ttf", weight = 400),
            DeckFontDeclaration(family = "SafeFamily", src = "fonts/safe-bold.ttf", weight = 700),
            DeckFontDeclaration(family = "UnsafeFamily", src = "fonts/unsafe.ttf", weight = 400),
            DeckFontDeclaration(family = "CorruptFamily", src = "fonts/corrupt.ttf", weight = 400),
            DeckFontDeclaration(family = "SysFamily", system = true, fallback = "SafeFamily"),
        )
        val byteMap = mapOf(
            "fonts/safe-reg.ttf" to safeRegularBytes,
            "fonts/safe-bold.ttf" to safeBoldBytes,
            "fonts/unsafe.ttf" to unsafeRequiredLigBytes,
            "fonts/corrupt.ttf" to corruptedBytes,
        )

        val resolver = DeckFontResolver(
            declarations = declarations,
            resourceLoader = FontResourceLoader { byteMap[it] },
            systemFontLookup = SystemFontLookup { name ->
                if (name == "SysFamily") {
                    SystemFontMatch("SysFamily", FontFamily.SansSerif, listOf(FontFaceKey(400, false)))
                } else {
                    null
                }
            },
        )

        assertNotNull(resolver.resolveFace("SafeFamily", FontWeight(400), FontStyle.Normal))
        assertNotNull(resolver.resolveFace("SafeFamily", FontWeight(700), FontStyle.Normal))
        assertNotNull(resolver.resolveFace("UnsafeFamily", FontWeight(400), FontStyle.Normal))
        assertNotNull(resolver.resolveFace("CorruptFamily", FontWeight(400), FontStyle.Normal))
        assertNotNull(resolver.resolveFace("SysFamily", FontWeight(400), FontStyle.Normal))

        // Missing italic face on SafeFamily must return null (no synthetic oblique).
        assertNull(resolver.resolveFace("SafeFamily", FontWeight(400), FontStyle.Italic))
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello", "SafeFamily", FontWeight(400), FontStyle.Italic))

        // 1. Word in certified bundled face with full coverage -> TRUE
        assertTrue(isSingleWordParagraphSafe(resolver, "Hello", "SafeFamily", FontWeight(400)))

        // 2. Word needing fallback for one uncovered code point ('!') -> FALSE
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello!", "SafeFamily", FontWeight(400)))

        // 3. Word mixing two certified faces (regular + bold span inside one word) -> TRUE
        val mixedSafeAnnotated = buildAnnotatedString {
            append("Hello")
            addStyle(SpanStyle(fontWeight = FontWeight(700)), 2, 5)
        }
        val safeBaseStyle = resolver.resolveTextStyle(
            "SafeFamily",
            TextStyle(fontWeight = FontWeight(400)),
            disableOptionalLigatures = true,
        )
        assertEquals(CHARACTER_DELIVERY_DISABLED_FEATURES, safeBaseStyle.fontFeatureSettings)
        assertTrue(isSingleWordParagraphSafe(resolver, mixedSafeAnnotated, safeBaseStyle))

        // 4. Word mixing certified face and uncertified (rlig) face -> FALSE
        val mixedUnsafeAnnotated = buildAnnotatedString {
            append("Hello")
            addStyle(
                resolver.resolveSpanStyle("UnsafeFamily", SpanStyle(fontWeight = FontWeight(400))),
                3,
                5,
            )
        }
        assertFalse(isSingleWordParagraphSafe(resolver, mixedUnsafeAnnotated, safeBaseStyle))

        // 5. Word in system face or corrupted face -> FALSE
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello", "SysFamily", FontWeight(400)))
        assertFalse(isSingleWordParagraphSafe(resolver, "Hello", "CorruptFamily", FontWeight(400)))

        // 6. Real bundled Nanum Pen Script certifies on Latin text and rejects uncovered emoji.
        val nanumResolver = DeckFontResolver(
            declarations = listOf(BundledFontCatalog.NanumPenScript),
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )
        assertTrue(isSingleWordParagraphSafe(nanumResolver, "Notes", "Nanum Pen Script", FontWeight.Normal))
        assertFalse(isSingleWordParagraphSafe(nanumResolver, "Notes🔥", "Nanum Pen Script", FontWeight.Normal))

        // 7. ALL 9 bundled Latin families certify "awesome", "function", "Shaders"
        // under CharacterForcedOff (and refuse under ShaperDefaults).
        val allBundledResolver = DeckFontResolver(
            declarations = BundledFontCatalog.AllBundled,
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )
        val bundledLatinFamilies = listOf(
            "Inter" to FontStyle.Normal,
            "JetBrains Mono" to FontStyle.Normal,
            "Hanken Grotesk" to FontStyle.Normal,
            "Hanken Grotesk" to FontStyle.Italic,
            "Fredoka" to FontStyle.Normal,
            "Assistant" to FontStyle.Normal,
            "Plus Jakarta Sans" to FontStyle.Normal,
            "Montserrat" to FontStyle.Normal,
            "Roboto" to FontStyle.Normal,
            "Nanum Pen Script" to FontStyle.Normal,
        )
        for ((family, fontStyle) in bundledLatinFamilies) {
            val style = allBundledResolver.resolveTextStyle(
                familyName = family,
                style = TextStyle(fontWeight = FontWeight.Normal, fontStyle = fontStyle),
                disableOptionalLigatures = true,
            )
            for (word in listOf("awesome", "function", "Shaders", "Typography", "fi")) {
                assertTrue(
                    isSingleWordParagraphSafe(allBundledResolver, word, family, FontWeight.Normal, fontStyle),
                    "Expected '$word' to be character-safe in $family ($fontStyle)",
                )
                val defaultCert = measureAndCertifyParagraph(
                    resolver = allBundledResolver,
                    paragraph = AnnotatedString(word),
                    baseStyle = style.copy(fontFeatureSettings = null),
                    effectiveConfig = EffectiveFontFeatureConfig.ShaperDefaults,
                )
                assertFalse(
                    defaultCert.isWordRangeSafe(0, word.length),
                    "ShaperDefaults must refuse '$word' in $family ($fontStyle)",
                )
            }
        }
        // Combining acute accent fails NFC + category allow-list -> refuses paragraph.
        assertFalse(
            isSingleWordParagraphSafe(allBundledResolver, "e\u0301", "Inter", FontWeight.Normal),
            "Combining mark sequence in Inter must be refused",
        )
        // Double backtick triggers JetBrains Mono's default-on ccmp contextual rule -> must reject.
        assertFalse(
            isSingleWordParagraphSafe(allBundledResolver, "``", "JetBrains Mono", FontWeight.Normal),
            "Contextual backtick sequence in JetBrains Mono ccmp must be rejected",
        )
    }

    @Test
    fun `config guard, Tier B closure, preconditions, disagreeing cmap, and mixed paragraphs`() {
        val baseInterBytes = File(bundledFontsDir(), "Inter-Regular.ttf").readBytes()
        val baseResolver = DeckFontResolver(
            declarations = listOf(DeckFontDeclaration(family = "BaseInter", src = "inter.ttf", weight = 400)),
            resourceLoader = FontResourceLoader { if (it == "inter.ttf") baseInterBytes else null },
            systemFontLookup = SystemFontLookup { null },
        )
        val baseFace = assertNotNull(baseResolver.resolveFace("BaseInter", FontWeight.Normal, FontStyle.Normal))
        val baseTf = assertNotNull(baseResolver.renderingTypeface(baseFace))
        fun gid(ch: Char): Int = baseTf.getUTF32Glyph(ch.code).toInt() and 0xFFFF

        val gA = gid('a')
        val gB = gid('b')
        val gC = gid('c')
        val gD = gid('d')
        val gF = gid('f')
        val gI = gid('i')
        val gX = gid('X')
        val gZ = gid('z')
        val gAltA = gid('Q') // distinct existing glyph in Inter
        val gAltC = gid('W')
        val gLig = gid('Z')
        val gExp1 = gid('K')
        val gExp2 = gid('M')

        fun repackWithGsub(gsub: SyntheticTtfBuilder.GsubSpec): ByteArray =
            SyntheticTtfBuilder.repackSfnt(
                baseBytes = baseInterBytes,
                replacements = mapOf("GSUB" to SyntheticTtfBuilder.buildGsubTable(gsub)),
            )

        fun resolverForSingleFace(bytes: ByteArray, family: String = "TestFont"): DeckFontResolver =
            DeckFontResolver(
                declarations = listOf(DeckFontDeclaration(family = family, src = "$family.ttf", weight = 400)),
                resourceLoader = FontResourceLoader { if (it == "$family.ttf") bytes else null },
                systemFontLookup = SystemFontLookup { null },
            )

        fun certifyWords(
            resolver: DeckFontResolver,
            text: String,
            family: String = "TestFont",
            config: EffectiveFontFeatureConfig = EffectiveFontFeatureConfig.CharacterForcedOff,
            isLtr: ((Int) -> Boolean)? = null,
            limits: GsubParserLimits = GsubParserLimits.Default,
        ): Pair<ParagraphCharacterSafetyCertifier, Map<String, Boolean>> {
            val style = resolver.resolveTextStyle(
                family,
                TextStyle(fontWeight = FontWeight.Normal),
                disableOptionalLigatures = config == EffectiveFontFeatureConfig.CharacterForcedOff,
            )
            val cert = measureAndCertifyParagraph(
                resolver = resolver,
                paragraph = AnnotatedString(text),
                baseStyle = style,
                effectiveConfig = config,
                isLtrOverride = isLtr,
                limits = limits,
            )
            val wordRanges = Regex("\\S+").findAll(text).map { it.range.first to (it.range.last + 1) }.toList()
            val results = cert.certifyHalfOpenWordRanges(wordRanges)
            val byWord = wordRanges.indices.associate { idx ->
                val (s, e) = wordRanges[idx]
                text.substring(s, e) to results[idx]
            }
            return cert to byWord
        }

        // 1. Configuration guard: alternating ShaperDefaults and CharacterForcedOff on same liga font!
        CharacterSafetyEngine.clearTierACache()
        val ligaFontBytes = repackWithGsub(
            SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 4,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gF, gI), gLig)),
                            ),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(0))),
            ),
        )
        val ligaResolver = resolverForSingleFace(ligaFontBytes, "LigaFont")
        val beforeDefaults1 = CharacterSafetyEngine.tierAEvaluationCount.get()
        val (defCert1, defWords1) = certifyWords(ligaResolver, "fish cat", "LigaFont", EffectiveFontFeatureConfig.ShaperDefaults)
        assertEquals(beforeDefaults1, CharacterSafetyEngine.tierAEvaluationCount.get(), "ShaperDefaults must NOT inspect font")
        assertFalse(defCert1.isParagraphEligible)
        assertEquals(false, defWords1["fish"])
        assertEquals(false, defWords1["cat"])

        val (_, offWords1) = certifyWords(ligaResolver, "fish cat", "LigaFont", EffectiveFontFeatureConfig.CharacterForcedOff)
        assertTrue(CharacterSafetyEngine.tierAEvaluationCount.get() > beforeDefaults1, "CharacterForcedOff must run Tier A")
        assertEquals(true, offWords1["fish"])
        assertEquals(true, offWords1["cat"])

        val beforeDefaults2 = CharacterSafetyEngine.tierAEvaluationCount.get()
        val (_, defWords2) = certifyWords(ligaResolver, "fish cat", "LigaFont", EffectiveFontFeatureConfig.ShaperDefaults)
        assertEquals(beforeDefaults2, CharacterSafetyEngine.tierAEvaluationCount.get(), "ShaperDefaults must not leak CharacterForcedOff cache")
        assertEquals(false, defWords2["fish"])
        val (_, offWords2) = certifyWords(ligaResolver, "fish cat", "LigaFont", EffectiveFontFeatureConfig.CharacterForcedOff)
        assertEquals(true, offWords2["fish"])

        // 2. Tier B: ccmp ligature f+i -> cat safe, fish & if unsafe
        val ccmpLigResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gF, gI), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
                ),
            ),
        )
        val (_, ccmpWords1) = certifyWords(ccmpLigResolver, "cat fish if")
        assertEquals(true, ccmpWords1["cat"])
        assertEquals(false, ccmpWords1["fish"])
        assertEquals(false, ccmpWords1["if"])

        // 3. Tier B: ccmp contextual rule with lookahead X in ANOTHER word
        val lookaheadResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gA to gAltA))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 6,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildChainedFormat1Subtable(
                                    backtrackGlyphs = emptyList(),
                                    firstInputGlyph = gA,
                                    remainingInputGlyphs = emptyList(),
                                    lookaheadGlyphs = listOf(gX),
                                    seqLookups = listOf(SyntheticTtfBuilder.SeqLookupRec(0, 0)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(1))),
                ),
            ),
        )
        assertEquals(true, certifyWords(lookaheadResolver, "ab cd").second["ab"])
        assertEquals(false, certifyWords(lookaheadResolver, "ab X").second["ab"])

        // 4. Tier B: ab cd with ligature output L used as backtrack of c
        val ligFeedsBacktrackResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gA, gB), gLig)),
                                ),
                            ),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gC to gAltC))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 6,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildChainedFormat1Subtable(
                                    backtrackGlyphs = listOf(gLig),
                                    firstInputGlyph = gC,
                                    remainingInputGlyphs = emptyList(),
                                    lookaheadGlyphs = emptyList(),
                                    seqLookups = listOf(SyntheticTtfBuilder.SeqLookupRec(0, 1)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0, 2))),
                ),
            ),
        )
        assertEquals(false, certifyWords(ligFeedsBacktrackResolver, "ab cd").second["cd"])
        assertEquals(true, certifyWords(ligFeedsBacktrackResolver, "az cd").second["cd"])

        // 5. Tier B: context-free Type 8 (a -> a.alt) feeding ccmp ligature (a.alt + b)
        val revChainFeedsLigResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 8,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildReverseChainedFormat1Subtable(
                                    inputPairs = listOf(gA to gAltA),
                                    backCoverages = emptyList(),
                                    lookCoverages = emptyList(),
                                ),
                            ),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gAltA, gB), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0, 1))),
                ),
            ),
        )
        val (_, revChainWords) = certifyWords(revChainFeedsLigResolver, "ab cd")
        assertEquals(false, revChainWords["ab"])
        assertEquals(true, revChainWords["cd"])

        // 6. Tier B: locl single subst (a -> a.alt) feeding ccmp ligature (a.alt + b)
        val loclFeedsLigResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gA to gAltA))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gAltA, gB), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(
                        SyntheticTtfBuilder.GsubFeatureSpec("locl", listOf(0)),
                        SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(1)),
                    ),
                ),
            ),
        )
        assertEquals(false, certifyWords(loclFeedsLigResolver, "ab cd").second["ab"])
        assertEquals(true, certifyWords(loclFeedsLigResolver, "ab cd").second["cd"])

        // 7. Tier B: Format 2 Class 0 (unlisted, explicit single 0, explicit range 0) + first-position Coverage ∩ Class
        val class0Unlisted = SyntheticTtfBuilder.buildClassDefFormat1(startGlyph = gX, classValues = listOf(1)) // gA unlisted => class 0
        val class0ExplicitSingle = SyntheticTtfBuilder.buildClassDefFormat1(startGlyph = gA, classValues = listOf(0)) // gA explicit 0
        val class0ExplicitRange = SyntheticTtfBuilder.buildClassDefFormat2(listOf(Triple(minOf(gA, gB), maxOf(gA, gB), 0)))
        for ((label, clsBytes) in listOf(
            "unlisted" to class0Unlisted,
            "explicit-single-0" to class0ExplicitSingle,
            "explicit-range-0" to class0ExplicitRange,
        )) {
            val fmt2Resolver = resolverForSingleFace(
                repackWithGsub(
                    SyntheticTtfBuilder.GsubSpec(
                        lookups = listOf(
                            SyntheticTtfBuilder.GsubLookupSpec(
                                lookupType = 5,
                                rawSubtables = listOf(
                                    SyntheticTtfBuilder.buildContextFormat2Subtable(
                                        coverageBytes = SyntheticTtfBuilder.buildCoverageFormat1(listOf(gA)),
                                        classDefBytes = clsBytes,
                                        firstClass = 0,
                                        remainingClasses = listOf(0),
                                    ),
                                ),
                            ),
                        ),
                        features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
                    ),
                ),
            )
            // "ab" has gA (in coverage ∩ class 0) and gB (in class 0) -> rule fires -> unsafe!
            assertEquals(false, certifyWords(fmt2Resolver, "ab").second["ab"], "Class 0 ($label) must refuse 'ab'")
            // "cd" has gC, gD in class 0, but NEITHER is in Coverage {gA} -> first position Coverage ∩ Class is empty -> safe!
            assertEquals(true, certifyWords(fmt2Resolver, "cd").second["cd"], "Coverage ∩ Class ($label) must allow 'cd'")
        }

        // 8. Tier B: nested lookup of contextual rule whose output feeds a ligature
        val nestedFeedsLigResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gA to gAltA))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 5,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildContextFormat1Subtable(
                                    firstGlyph = gA,
                                    remainingGlyphs = emptyList(),
                                    seqLookups = listOf(SyntheticTtfBuilder.SeqLookupRec(0, 0)),
                                ),
                            ),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gAltA, gB), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(1, 2))),
                ),
            ),
        )
        assertEquals(false, certifyWords(nestedFeedsLigResolver, "ab cd").second["ab"])
        assertEquals(true, certifyWords(nestedFeedsLigResolver, "ab cd").second["cd"])

        // 9. Tier B: Always-On FeatureVariations single subst feeding ccmp ligature
        val varSingleFeedsCcmpResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    minorVersion = 1,
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(gA to gAltA))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gAltA, gB), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(
                        SyntheticTtfBuilder.GsubFeatureSpec("ss01", emptyList()),
                        SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(1)),
                    ),
                    featureVariations = listOf(
                        listOf(SyntheticTtfBuilder.GsubVariationSubstitution(featureIndex = 0, alternateLookupIndices = listOf(0))),
                    ),
                ),
            ),
        )
        assertEquals(false, certifyWords(varSingleFeedsCcmpResolver, "ab cd").second["ab"])
        assertEquals(true, certifyWords(varSingleFeedsCcmpResolver, "ab cd").second["cd"])

        // 10. Tier B: non-empty Type 2 expansion (a -> gExp1, gExp2) feeding multi-glyph (gExp1 + b)
        val type2FeedsLigResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 2,
                            rawSubtables = listOf(SyntheticTtfBuilder.buildMultipleFormat1Subtable(listOf(gA to listOf(gExp1, gExp2)))),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gExp1, gB), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(
                        SyntheticTtfBuilder.GsubFeatureSpec("rvrn", listOf(0)),
                        SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(1)),
                    ),
                ),
            ),
        )
        assertEquals(false, certifyWords(type2FeedsLigResolver, "ab cd").second["ab"])
        assertEquals(true, certifyWords(type2FeedsLigResolver, "ab cd").second["cd"])

        // 11. Fixed-point budget exhaustion refuses ALL words of paragraph!
        val chainResolver = resolverForSingleFace(
            repackWithGsub(
                SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 1,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildSingleFormat2Subtable(
                                    listOf(gA to gAltA, gAltA to gExp1, gExp1 to gExp2),
                                ),
                            ),
                        ),
                        SyntheticTtfBuilder.GsubLookupSpec(
                            lookupType = 4,
                            rawSubtables = listOf(
                                SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                    listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gExp2, gZ), gLig)),
                                ),
                            ),
                        ),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0, 1))),
                ),
            ),
        )
        val (exhaustedCert, exhaustedWords) = certifyWords(
            resolver = chainResolver,
            text = "ab cd",
            limits = GsubParserLimits(maxFixedPointRounds = 1),
        )
        assertTrue(exhaustedCert.budgetExhausted, "Fixed-point round limit 1 must report exhaustion")
        assertEquals(false, exhaustedWords["ab"])
        assertEquals(false, exhaustedWords["cd"], "Budget exhaustion must refuse ALL words in paragraph")

        // 12. Preconditions: every forbidden construct refuses ALL words; valid constructs pass!
        val allBundled = DeckFontResolver(
            declarations = BundledFontCatalog.AllBundled,
            resourceLoader = realBundleResourceLoader(),
            systemFontLookup = SystemFontLookup { null },
        )
        val forbiddenParagraphs = listOf(
            "combining-mark" to "Hello cafe\u0301",
            "non-nfc-angstrom" to "Hello \u212B",
            "variation-selector" to "Hello ⏩\uFE0F",
            "zwj" to "Hello 👨\u200D🍳",
            "tab" to "Hello\tworld",
            "soft-hyphen" to "Hello soft\u00ADhyphen",
            "unpaired-surrogate" to "Hello \uD800world",
            "hangul-syllable" to "Hello 한글",
            "hangul-jamo" to "Hello \u1100\u1161",
            "fraction-slash" to "Hello 1\u20442",
            "division-slash" to "Hello 1\u22152",
            "uncovered-glyph-refuses-whole-paragraph" to "Hello \u2726world",
        )
        for ((label, para) in forbiddenParagraphs) {
            val (c, words) = certifyWords(allBundled, para, family = "Inter")
            assertFalse(c.isParagraphEligible, "Precondition '$label' must refuse paragraph")
            assertEquals(false, words["Hello"], "Precondition '$label' must refuse even covered word 'Hello'")
        }
        // RTL layout direction refuses paragraph even on ASCII text!
        val (rtlCert, rtlWords) = certifyWords(allBundled, "Hello world", family = "Inter", isLtr = { false })
        assertFalse(rtlCert.isParagraphEligible, "RTL layout must refuse paragraph")
        assertEquals(false, rtlWords["Hello"])

        // Valid NFC precomposed accents, ASCII slash, line feed, plain emoji (no joiner/selector) are judged normally!
        val (_, okAccent) = certifyWords(allBundled, "café 1/2\nworld", family = "Inter")
        assertEquals(true, okAccent["café"])
        assertEquals(true, okAccent["1/2"])
        assertEquals(true, okAccent["world"])

        val (_, plainEmojiWords) = certifyWords(allBundled, "Hello 🔨", family = "Hanken Grotesk")
        assertEquals(true, plainEmojiWords["Hello"], "Plain emoji routed to Noto Emoji must allow paragraph")
        assertEquals(true, plainEmojiWords["🔨"], "Plain emoji without joiner/selector is judged normally")

        // Supplementary-plane Han character in synthetic covering font passes preconditions!
        val suppCp = 0x20000
        val suppHanStr = "A" + String(Character.toChars(suppCp))
        val suppBytes = SyntheticTtfBuilder.buildTtf(
            bmpCodePoints = setOf('A'.code, ' '.code),
            fullUnicodeCodePoints = setOf('A'.code, ' '.code, suppCp),
        )
        val suppResolver = resolverForSingleFace(suppBytes, "SuppHan")
        assertEquals(true, certifyWords(suppResolver, suppHanStr, "SuppHan").second[suppHanStr])

        // 13. Disagreeing cmap subtables: Skia picks Format 12 (3,10) over Format 4 (3,1)!
        // Format 4 maps 'a'->10, 'b'->11; Format 12 maps 'a'->gA, 'b'->gB; ccmp ligature targets (gA, gB)!
        val disagreeCmap = SyntheticTtfBuilder.buildCustomCmapTable(
            listOf(
                Triple(3, 1, SyntheticTtfBuilder.buildCmapFormat4FromMap(mapOf('a'.code to 10, 'b'.code to 11, 'c'.code to 12, 'd'.code to 13, ' '.code to 3))),
                Triple(3, 10, SyntheticTtfBuilder.buildCmapFormat12FromMap(mapOf('a'.code to gA, 'b'.code to gB, 'c'.code to gC, 'd'.code to gD, ' '.code to gid(' ')))),
            ),
        )
        val disagreeBytes = SyntheticTtfBuilder.repackSfnt(
            baseBytes = baseInterBytes,
            replacements = mapOf(
                "cmap" to disagreeCmap,
                "GSUB" to SyntheticTtfBuilder.buildGsubTable(
                    SyntheticTtfBuilder.GsubSpec(
                        lookups = listOf(
                            SyntheticTtfBuilder.GsubLookupSpec(
                                lookupType = 4,
                                rawSubtables = listOf(
                                    SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                        listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gA, gB), gLig)),
                                    ),
                                ),
                            ),
                        ),
                        features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
                    ),
                ),
            ),
        )
        val disagreeResolver = resolverForSingleFace(disagreeBytes, "DisagreeCmap")
        val (_, disagreeWords) = certifyWords(disagreeResolver, "ab cd", "DisagreeCmap")
        assertEquals(false, disagreeWords["ab"], "Disagreeing cmap must use Skia rendering glyph IDs (Format 12) and refuse 'ab'")
        assertEquals(true, disagreeWords["cd"], "Unrelated 'cd' in disagreeing cmap font remains safe")

        // Glyph 0 mapping entry counts as uncovered -> refuses paragraph!
        val zeroGlyphCmap = SyntheticTtfBuilder.buildCustomCmapTable(
            listOf(
                Triple(3, 1, SyntheticTtfBuilder.buildCmapFormat4FromMap(mapOf('a'.code to gA, 'b'.code to 0, ' '.code to gid(' ')))),
            ),
        )
        val zeroGlyphBytes = SyntheticTtfBuilder.repackSfnt(
            baseBytes = baseInterBytes,
            replacements = mapOf("cmap" to zeroGlyphCmap),
        )
        val zeroGlyphResolver = resolverForSingleFace(zeroGlyphBytes, "ZeroGlyph")
        val (zeroCert, zeroWords) = certifyWords(zeroGlyphResolver, "a b", "ZeroGlyph")
        assertFalse(zeroCert.isParagraphEligible, "Explicit glyph 0 mapping must count as uncovered and refuse paragraph")
        assertEquals(false, zeroWords["a"])

        // 14. Mixed paragraphs: Tier-A rejected face leaves words wholly in safe face judged normally!
        val badRligBytes = repackWithGsub(
            SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 4,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildLigatureFormat1Subtable(
                                listOf(SyntheticTtfBuilder.LigatureEntry(listOf(gF, gI), gLig)),
                            ),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
            ),
        )
        val mixedResolver = DeckFontResolver(
            declarations = listOf(
                DeckFontDeclaration(family = "Good", src = "good.ttf", weight = 400),
                DeckFontDeclaration(family = "GoodBold", src = "good-bold.ttf", weight = 700),
                DeckFontDeclaration(family = "BadTierA", src = "bad.ttf", weight = 400),
            ),
            resourceLoader = FontResourceLoader { src ->
                when (src) {
                    "good.ttf", "good-bold.ttf" -> baseInterBytes
                    "bad.ttf" -> badRligBytes
                    else -> null
                }
            },
            systemFontLookup = SystemFontLookup { null },
        )
        val goodStyle = mixedResolver.resolveTextStyle("Good", TextStyle(fontWeight = FontWeight.Normal))
        val goodBoldFam = assertNotNull(mixedResolver.resolveFamily("GoodBold")).fontFamily
        val badFam = assertNotNull(mixedResolver.resolveFamily("BadTierA")).fontFamily
        val mixedPara = buildAnnotatedString {
            append("Safe ") // [0, 4) wholly in Good
            append("Mi") // [5, 10) mixes Good + BadTierA
            pushStyle(SpanStyle(fontFamily = badFam))
            append("xed")
            pop()
            append(" ")
            append("Tw") // [11, 14) mixes Good + GoodBold (two safe faces)
            pushStyle(SpanStyle(fontFamily = goodBoldFam, fontWeight = FontWeight.Bold))
            append("o")
            pop()
        }
        val mixedCert = measureAndCertifyParagraph(
            resolver = mixedResolver,
            paragraph = mixedPara,
            baseStyle = goodStyle,
            effectiveConfig = EffectiveFontFeatureConfig.CharacterForcedOff,
        )
        assertTrue(mixedCert.isParagraphEligible)
        assertTrue(mixedCert.isWordRangeSafe(0, 4), "Word wholly in safe face must be safe despite Tier-A rejected face elsewhere in paragraph")
        assertFalse(mixedCert.isWordRangeSafe(5, 10), "Word mixing safe and Tier-A rejected face must stay whole")
        assertTrue(mixedCert.isWordRangeSafe(11, 14), "Word mixing two safe bundled faces may divide")
    }

    @Test
    fun `DeckFontDeclaration serializes and deserializes cleanly for bundled, variable, emoji, and system entries`() {
        val sample = listOf(
            BundledFontCatalog.Inter.first(),
            BundledFontCatalog.HankenGrotesk.first(),
            BundledFontCatalog.HankenGrotesk.last(),
            BundledFontCatalog.NotoEmoji,
            DeckFontDeclaration(family = "Oscine", system = true, fallback = "Plus Jakarta Sans"),
        )
        val json = Json { encodeDefaults = false }
        val encoded = json.encodeToString(sample)
        val decoded = json.decodeFromString<List<DeckFontDeclaration>>(encoded)
        assertEquals(sample, decoded)
    }

    private fun renderTextPixels(text: String, style: TextStyle): ByteArray {
        val width = 320
        val height = 120
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(width.dp, height.dp)
                    .background(Color.Black),
            ) {
                Text(text = text, style = style)
            }
        }
        return try {
            val image = scene.render(0L)
            try {
                val bitmap = Bitmap()
                bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
                assertTrue(image.readPixels(bitmap, 0, 0))
                val bytes = assertNotNull(bitmap.readPixels())
                // Verify non-empty drawing occurred.
                var litPixels = 0
                for (i in bytes.indices step 4) {
                    val r = bytes[i].toInt() and 0xFF
                    val g = bytes[i + 1].toInt() and 0xFF
                    val b = bytes[i + 2].toInt() and 0xFF
                    if (abs(r) + abs(g) + abs(b) > 0) litPixels++
                }
                assertTrue(litPixels > 50, "Expected visible rendered glyphs, found $litPixels lit pixels")
                bytes
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }
}
