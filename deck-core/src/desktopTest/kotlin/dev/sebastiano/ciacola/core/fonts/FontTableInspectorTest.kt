package dev.sebastiano.ciacola.core.fonts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontTableInspectorTest {

    private fun bundledFontsDir(): File {
        val candidates = listOf(
            File("deck/src/desktopMain/resources/fonts"),
            File("../deck/src/desktopMain/resources/fonts"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate deck/src/desktopMain/resources/fonts from ${File(".").absolutePath}")
    }

    @Test
    fun `every bundled TTF in deck resources parses cleanly without corruption`() {
        val dir = bundledFontsDir()
        val ttfFiles = dir.listFiles { f -> f.isFile && f.extension.equals("ttf", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(ttfFiles.size >= 30, "Expected at least 30 bundled TTFs, found ${ttfFiles.size}")

        for (file in ttfFiles) {
            val inspection = FontTableInspector.inspect(file.readBytes(), useCache = false)
            assertTrue(inspection.validSfnt, "${file.name} should have a valid SFNT header")
            assertNull(inspection.parseError, "${file.name} failed table parse: ${inspection.parseError}")
            assertNotNull(inspection.cmapCoverage, "${file.name} must expose Unicode cmap coverage")
            assertTrue(inspection.unsupportedShapingTables.isEmpty(), "${file.name} has unsupported shaping tables")
        }
    }

    @Test
    fun `real bundled variable fonts report exact fvar wght axes and cmap coverage`() {
        val dir = bundledFontsDir()

        val hanken = FontTableInspector.inspect(File(dir, "HankenGrotesk-VariableFont_wght.ttf").readBytes())
        assertEquals(100f, hanken.weightAxis?.minValue)
        assertEquals(400f, hanken.weightAxis?.defaultValue)
        assertEquals(900f, hanken.weightAxis?.maxValue)
        assertTrue(hanken.cmapCoverage!!.hasGlyph('A'.code))
        assertFalse(hanken.cmapCoverage!!.hasGlyph(0x1F528)) // 🔨 not in Hanken Grotesk

        val hankenItalic = FontTableInspector.inspect(File(dir, "HankenGrotesk-Italic-VariableFont_wght.ttf").readBytes())
        assertEquals(100f, hankenItalic.weightAxis?.minValue)
        assertEquals(900f, hankenItalic.weightAxis?.maxValue)
        assertEquals(true, hankenItalic.os2IsItalic)

        val notoEmoji = FontTableInspector.inspect(File(dir, "NotoEmoji-VariableFont_wght.ttf").readBytes())
        assertEquals(300f, notoEmoji.weightAxis?.minValue)
        assertEquals(400f, notoEmoji.weightAxis?.defaultValue)
        assertEquals(700f, notoEmoji.weightAxis?.maxValue)
        assertTrue(12 in notoEmoji.cmapCoverage!!.formatsPresent)
        assertTrue(notoEmoji.cmapCoverage!!.hasGlyph(0x1F528)) // 🔨
        assertTrue(notoEmoji.cmapCoverage!!.hasGlyph(0x1F496)) // 💖
        assertTrue(notoEmoji.cmapCoverage!!.hasGlyph(0x1F525)) // 🔥
        assertTrue(notoEmoji.cmapCoverage!!.hasGlyph(0x23E9)) // ⏩
        assertFalse(notoEmoji.cmapCoverage!!.hasGlyph('A'.code))

        val fredoka = FontTableInspector.inspect(File(dir, "Fredoka-VariableFont_wdth_wght.ttf").readBytes())
        assertEquals(300f, fredoka.weightAxis?.minValue)
        assertEquals(700f, fredoka.weightAxis?.maxValue)
        assertEquals(75f, fredoka.variationAxes.first { it.tag == "wdth" }.minValue)

        val googleSans = FontTableInspector.inspect(File(dir, "GoogleSans-VariableFont_GRAD_opsz_wght.ttf").readBytes())
        assertTrue(googleSans.isStructurallyInspectable)
        assertEquals(400f, googleSans.weightAxis?.minValue)
        assertEquals(700f, googleSans.weightAxis?.maxValue)
        assertEquals(17f, googleSans.variationAxes.first { it.tag == "opsz" }.minValue)
        assertEquals(-50f, googleSans.variationAxes.first { it.tag == "GRAD" }.minValue)
        assertEquals(false, googleSans.os2IsItalic)
        assertFalse(
            googleSans.passesTierAForcedOff,
            "Pan-script Google Sans carries AlwaysOn Arabic/Indic shaping features and must coarsen to Word delivery under Tier A",
        )

        val googleSansItalic = FontTableInspector.inspect(File(dir, "GoogleSans-Italic-VariableFont_GRAD_opsz_wght.ttf").readBytes())
        assertTrue(googleSansItalic.isStructurallyInspectable)
        assertEquals(400f, googleSansItalic.weightAxis?.minValue)
        assertEquals(700f, googleSansItalic.weightAxis?.maxValue)
        assertEquals(true, googleSansItalic.os2IsItalic)
        assertFalse(googleSansItalic.passesTierAForcedOff)

        val assistant = FontTableInspector.inspect(File(dir, "Assistant-VariableFont_wght.ttf").readBytes())
        assertEquals(200f, assistant.weightAxis?.minValue)
        assertEquals(800f, assistant.weightAxis?.maxValue)

        val montserrat = FontTableInspector.inspect(File(dir, "Montserrat-VariableFont_wght.ttf").readBytes())
        assertEquals(100f, montserrat.weightAxis?.minValue)
        assertEquals(900f, montserrat.weightAxis?.maxValue)
        assertEquals(1, montserrat.gsub.majorVersion)
        assertEquals(1, montserrat.gsub.minorVersion)
        assertTrue(montserrat.gsub.featureVariationCount > 0, "Montserrat GSUB v1.1 should report FeatureVariations")
        assertNull(montserrat.gsub.parseError)

        val nanum = FontTableInspector.inspect(File(dir, "NanumPenScript-Regular.ttf").readBytes())
        assertNull(nanum.weightAxis)
        assertEquals(400, nanum.os2WeightClass)
        assertTrue(nanum.isCharacterSafe, "NanumPenScript-Regular has no disallowed GSUB lookups and should certify")
    }

    @Test
    fun `inspection is cached by SHA-256 content hash`() {
        FontTableInspector.clearCache()
        val bytes = SyntheticTtfBuilder.buildTtf()
        val first = FontTableInspector.inspect(bytes, useCache = true)
        val second = FontTableInspector.inspect(bytes.copyOf(), useCache = true)
        assertSame(first, second)
        assertEquals(64, first.contentSha256.length)
    }

    @Test
    fun `cmap Format 4 direct delta, Format 4 indirect glyphIdArray, and Format 12 supplementary plane`() {
        val direct = FontTableInspector.inspect(
            SyntheticTtfBuilder.buildTtf(
                bmpCodePoints = setOf('A'.code, 'B'.code, 'Z'.code, 'é'.code),
                useIndirectFormat4 = false,
            ),
            useCache = false,
        )
        assertTrue(direct.isCharacterSafe)
        assertTrue(direct.cmapCoverage!!.hasGlyph('A'.code))
        assertTrue(direct.cmapCoverage!!.hasGlyph('é'.code))
        assertFalse(direct.cmapCoverage!!.hasGlyph('C'.code))
        assertFalse(direct.cmapCoverage!!.hasGlyph(0x1F525))

        val indirect = FontTableInspector.inspect(
            SyntheticTtfBuilder.buildTtf(
                bmpCodePoints = setOf('H'.code, 'e'.code, 'l'.code, 'o'.code),
                useIndirectFormat4 = true,
            ),
            useCache = false,
        )
        assertTrue(indirect.isCharacterSafe)
        assertTrue(indirect.cmapCoverage!!.hasGlyph('H'.code))
        assertTrue(indirect.cmapCoverage!!.hasGlyph('o'.code))
        assertFalse(indirect.cmapCoverage!!.hasGlyph('X'.code))

        val fullUnicode = FontTableInspector.inspect(
            SyntheticTtfBuilder.buildTtf(
                bmpCodePoints = setOf('A'.code),
                fullUnicodeCodePoints = setOf('A'.code, 0x1F525, 0x1F600, 0x1F9E9),
            ),
            useCache = false,
        )
        assertTrue(fullUnicode.isCharacterSafe)
        assertEquals(setOf(4, 12), fullUnicode.cmapCoverage!!.formatsPresent)
        assertTrue(fullUnicode.cmapCoverage!!.hasGlyph('A'.code))
        assertTrue(fullUnicode.cmapCoverage!!.hasGlyph(0x1F525))
        assertTrue(fullUnicode.cmapCoverage!!.hasGlyph(0x1F9E9))
        assertFalse(fullUnicode.cmapCoverage!!.hasGlyph(0x1F528))
    }

    @Test
    fun `bundled font with no GSUB or only optional liga clig dlig calt and simple lookups is certified`() {
        val noGsub = FontTableInspector.inspect(SyntheticTtfBuilder.buildTtf(gsub = null), useCache = false)
        assertTrue(noGsub.isCharacterSafe)

        val optionalOnly = FontTableInspector.inspect(
            SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(1), // Single
                        SyntheticTtfBuilder.GsubLookupSpec(2), // Multiple
                        SyntheticTtfBuilder.GsubLookupSpec(3), // Alternate
                        SyntheticTtfBuilder.GsubLookupSpec(4), // Ligature
                        SyntheticTtfBuilder.GsubLookupSpec(5), // Context
                        SyntheticTtfBuilder.GsubLookupSpec(6), // Chaining Context
                        SyntheticTtfBuilder.GsubLookupSpec(8), // Reverse Chaining
                        SyntheticTtfBuilder.GsubLookupSpec(7, extensionInnerTypes = listOf(4, 6)), // Ext wrapping 4/6
                        SyntheticTtfBuilder.GsubLookupSpec(7, extensionInnerTypes = listOf(1, 2)), // Ext wrapping 1/2
                    ),
                    features = listOf(
                        SyntheticTtfBuilder.GsubFeatureSpec("smcp", listOf(0, 1, 2, 8)),
                        SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0, 1)),
                        SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(3, 7)),
                        SyntheticTtfBuilder.GsubFeatureSpec("clig", listOf(3)),
                        SyntheticTtfBuilder.GsubFeatureSpec("dlig", listOf(4, 6)),
                        SyntheticTtfBuilder.GsubFeatureSpec("calt", listOf(5, 7)),
                    ),
                ),
            ),
            useCache = false,
        )
        assertTrue(optionalOnly.isCharacterSafe, "Disallowed refs: ${optionalOnly.gsub.disallowedReferences}")
        assertTrue(optionalOnly.gsub.disallowedReferences.isEmpty())
    }

    @Test
    fun `required or non-exempt features with lookup types 4, 5, 6, 8 are each rejected`() {
        val cases = listOf(
            "rlig" to 4,
            "ccmp" to 5,
            "ccmp" to 6,
            "rlig" to 8,
        )
        for ((featureTag, lookupType) in cases) {
            val bytes = SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(lookupType)),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec(featureTag, listOf(0))),
                ),
            )
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertFalse(
                inspection.isCharacterSafe,
                "Expected $featureTag with LookupType $lookupType to fail character-safety certification",
            )
            assertNull(inspection.parseError)
            assertEquals(1, inspection.gsub.disallowedReferences.size)
            val ref = inspection.gsub.disallowedReferences.single()
            assertEquals(featureTag, ref.featureTag)
            assertEquals(lookupType, ref.lookupType)
            assertFalse(ref.viaExtension)
            assertFalse(ref.viaFeatureVariation)
        }
    }

    @Test
    fun `Extension LookupType 7 wrapping 4, 5, 6, or 8 is rejected`() {
        for (innerType in listOf(4, 5, 6, 8)) {
            val bytes = SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(7, extensionInnerTypes = listOf(1, innerType)),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
                ),
            )
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertFalse(
                inspection.isCharacterSafe,
                "Expected Extension LookupType 7 wrapping $innerType under rlig to be rejected",
            )
            val ref = inspection.gsub.disallowedReferences.single()
            assertEquals("rlig", ref.featureTag)
            assertEquals(innerType, ref.lookupType)
            assertTrue(ref.viaExtension)
        }
    }

    @Test
    fun `calt or liga marked as LangSys requiredFeatureIndex is rejected`() {
        for (tag in listOf("calt", "liga")) {
            val bytes = SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(4)),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec(tag, listOf(0))),
                    requiredFeatureIndex = 0,
                ),
            )
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertFalse(inspection.isCharacterSafe, "Required LangSys feature '$tag' must be rejected")
            val ref = inspection.gsub.disallowedReferences.single()
            assertEquals(tag, ref.featureTag)
            assertTrue(ref.viaRequiredFeature)
        }
    }

    @Test
    fun `GSUB v1_1 FeatureVariations injecting disallowed lookup into rvrn is rejected while type 1 passes`() {
        val unsafeVariation = SyntheticTtfBuilder.buildTtf(
            axes = listOf(SyntheticTtfBuilder.AxisSpec("wght", 100f, 400f, 900f)),
            gsub = SyntheticTtfBuilder.GsubSpec(
                minorVersion = 1,
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(1),
                    SyntheticTtfBuilder.GsubLookupSpec(7, extensionInnerTypes = listOf(6)),
                ),
                features = listOf(
                    // Default rvrn only references safe lookup 0 (Single Substitution).
                    SyntheticTtfBuilder.GsubFeatureSpec("rvrn", listOf(0)),
                ),
                featureVariations = listOf(
                    // Alternate feature table swaps in Extension->ChainingContext lookup 1!
                    listOf(SyntheticTtfBuilder.GsubVariationSubstitution(featureIndex = 0, alternateLookupIndices = listOf(1))),
                ),
            ),
        )
        val unsafeResult = FontTableInspector.inspect(unsafeVariation, useCache = false)
        assertFalse(unsafeResult.isCharacterSafe)
        assertNull(unsafeResult.parseError)
        val ref = unsafeResult.gsub.disallowedReferences.single()
        assertEquals("rvrn", ref.featureTag)
        assertEquals(6, ref.lookupType)
        assertTrue(ref.viaExtension)
        assertTrue(ref.viaFeatureVariation)

        val safeVariation = SyntheticTtfBuilder.buildTtf(
            axes = listOf(SyntheticTtfBuilder.AxisSpec("wght", 100f, 400f, 900f)),
            gsub = SyntheticTtfBuilder.GsubSpec(
                minorVersion = 1,
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(1),
                    SyntheticTtfBuilder.GsubLookupSpec(2),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rvrn", listOf(0))),
                featureVariations = listOf(
                    listOf(SyntheticTtfBuilder.GsubVariationSubstitution(featureIndex = 0, alternateLookupIndices = listOf(1))),
                ),
            ),
        )
        val safeResult = FontTableInspector.inspect(safeVariation, useCache = false)
        assertTrue(safeResult.isCharacterSafe, "Safe FeatureVariations (type 2) should certify")
    }

    @Test
    fun `unsupported AAT or Graphite shaping tables fail closed`() {
        for (tableTag in listOf("morx", "mort", "Silf")) {
            val bytes = SyntheticTtfBuilder.buildTtf(extraTables = mapOf(tableTag to ByteArray(16)))
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertFalse(inspection.isCharacterSafe, "Table '$tableTag' must reject character safety")
            assertTrue(tableTag in inspection.unsupportedShapingTables)
        }
    }

    @Test
    fun `corrupted and truncated font tables fail closed without throwing`() {
        val malformedFixtures: List<Pair<String, ByteArray>> = listOf(
            "empty" to ByteArray(0),
            "truncated-header" to ByteArray(8),
            "bad-magic" to ByteArray(32) { 0x42 },
            "ttcf-collection" to ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putInt(0x74746366).array(),
            "missing-cmap" to SyntheticTtfBuilder.buildTtf(omitCmap = true),
            "truncated-gsub" to SyntheticTtfBuilder.buildTtf(extraTables = mapOf("GSUB" to ByteArray(6))),
            "unknown-gsub-version" to SyntheticTtfBuilder.buildTtf(
                extraTables = mapOf(
                    "GSUB" to ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .putShort(2).putShort(0).putShort(10).putShort(10).putShort(10).array(),
                ),
            ),
            "unknown-lookup-type-9" to SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(9)),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(0))),
                ),
            ),
            "nested-extension-7-in-7" to SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(7, extensionInnerTypes = listOf(7))),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(0))),
                ),
            ),
            "oob-feature-lookup-index" to SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(1)),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("liga", listOf(99))),
                ),
            ),
            "bad-fvar-min-gt-max" to SyntheticTtfBuilder.buildTtf(
                axes = listOf(SyntheticTtfBuilder.AxisSpec("wght", 900f, 400f, 100f)),
            ),
            "truncated-fvar" to SyntheticTtfBuilder.buildTtf(
                extraTables = mapOf("fvar" to ByteArray(10)),
            ),
            "oob-table-record" to ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0x00010000)
                putShort(1) // numTables
                putShort(0); putShort(0); putShort(0)
                put("cmap".toByteArray())
                putInt(0)
                putInt(1000) // OOB offset
                putInt(100)
            }.array(),
        )

        for ((name, bytes) in malformedFixtures) {
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertFalse(inspection.isCharacterSafe, "Corrupted fixture '$name' must not certify as character-safe")
            assertNotNull(inspection.parseError, "Corrupted fixture '$name' must record a parseError")
        }
    }

    @Test
    fun `fvar parses wght, ital, and signed negative slnt axes accurately`() {
        val bytes = SyntheticTtfBuilder.buildTtf(
            axes = listOf(
                SyntheticTtfBuilder.AxisSpec("wght", 100f, 400f, 900f),
                SyntheticTtfBuilder.AxisSpec("ital", 0f, 0f, 1f),
                SyntheticTtfBuilder.AxisSpec("slnt", -12f, 0f, 0f),
            ),
        )
        val inspection = FontTableInspector.inspect(bytes, useCache = false)
        assertNull(inspection.parseError)
        assertEquals(VariationAxisRange("wght", 100f, 400f, 900f), inspection.weightAxis)
        assertEquals(VariationAxisRange("ital", 0f, 0f, 1f), inspection.italicAxis)
        assertEquals(VariationAxisRange("slnt", -12f, 0f, 0f), inspection.slantAxis)
    }

    @Test
    fun `opt-in GSUB features are excluded and unquoted feature string parses in Skiko`() {
        // Skiko 0.156.1 FontFeature.parseW3 requires unquoted tags.
        assertFalse(CHARACTER_DELIVERY_DISABLED_FEATURES.contains('"'))
        val parsed = org.jetbrains.skia.FontFeature.parseW3(CHARACTER_DELIVERY_DISABLED_FEATURES)
        assertEquals(4, parsed.size)
        assertEquals(listOf("liga", "clig", "dlig", "calt"), parsed.map { it.tag })
        assertTrue(parsed.all { it.value == 0 })

        // Opt-in HarfBuzz features (frac, ordn, ss01..ss20, cv01..cv99, smcp, tnum, etc.) must NOT
        // disqualify a face when they are not required LangSys features.
        val optInTags = listOf("frac", "ordn", "ss01", "ss03", "ss20", "cv01", "cv99", "smcp", "tnum", "cpsp")
        val optInBytes = SyntheticTtfBuilder.buildTtf(
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(4),
                    SyntheticTtfBuilder.GsubLookupSpec(6),
                ),
                features = optInTags.map { tag -> SyntheticTtfBuilder.GsubFeatureSpec(tag, listOf(0, 1)) },
            ),
        )
        val optInInspection = FontTableInspector.inspect(optInBytes, useCache = false)
        assertTrue(
            optInInspection.isCharacterSafe,
            "Opt-in GSUB features must be excluded when not enabled: ${optInInspection.gsub.disallowedReferences}",
        )
        assertTrue(optInInspection.passesTierAForcedOff)
        assertTrue(optInInspection.gsub.disallowedReferences.isEmpty())
    }

    @Test
    fun `real bundled fonts pass Tier A and certify awesome, function, Shaders`() {
        val dir = bundledFontsDir()
        val familiesToCheck = listOf(
            "Inter-Regular.ttf",
            "Inter-Bold.ttf",
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Bold.ttf",
            "HankenGrotesk-VariableFont_wght.ttf",
            "HankenGrotesk-Italic-VariableFont_wght.ttf",
            "Fredoka-VariableFont_wdth_wght.ttf",
            "Assistant-VariableFont_wght.ttf",
            "PlusJakartaSans-VariableFont_wght.ttf",
            "Montserrat-VariableFont_wght.ttf",
            "Roboto-VariableFont_wdth_wght.ttf",
            "NanumPenScript-Regular.ttf",
        )
        for (fileName in familiesToCheck) {
            val bytes = File(dir, fileName).readBytes()
            val inspection = FontTableInspector.inspect(bytes, useCache = false)
            assertTrue(
                inspection.isStructurallyInspectable,
                "$fileName must be structurally inspectable: parseError=${inspection.parseError}",
            )
            assertTrue(inspection.numGlyphs > 0, "$fileName must declare maxp.numGlyphs > 0")
            assertTrue(
                inspection.passesTierAForcedOff,
                "$fileName must pass Tier A: disallowed=${inspection.gsub.disallowedReferences.filter { it.featureClass == GsubFeatureClass.AlwaysOn }}",
            )
        }
    }

    @Test
    fun `Tier A rejects always-on rlig, required ccmp or ss01, FeatureVariations, cycles, missing maxp, and OOB glyph IDs`() {
        // 1. rlig with real Ligature Format 1 MUST fail Tier A for EVERY word (even 'Hello')!
        val bmp = ('A'..'Z').map { it.code }.toSet() + ('a'..'z').map { it.code }.toSet()
        val rligBytes = SyntheticTtfBuilder.buildTtf(
            bmpCodePoints = bmp,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 4,
                        ligatureGlyphSequences = listOf(listOf(32, 35)),
                        defaultLigatureGlyph = 60,
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
            ),
        )
        val rligInsp = FontTableInspector.inspect(rligBytes, useCache = false)
        assertTrue(rligInsp.isStructurallyInspectable)
        assertFalse(rligInsp.passesTierAForcedOff, "Always-on rlig multi-glyph must fail Tier A")

        // 2. Missing maxp fails closed.
        val noMaxp = FontTableInspector.inspect(SyntheticTtfBuilder.buildTtf(omitMaxp = true), useCache = false)
        assertFalse(noMaxp.isStructurallyInspectable)
        assertFalse(noMaxp.passesTierAForcedOff)
        assertNotNull(noMaxp.parseError)

        // 3. Glyph ID == numGlyphs fails closed; glyph ID == numGlyphs - 1 succeeds!
        val domainN = 128
        val atEdgeValid = SyntheticTtfBuilder.buildTtf(
            explicitNumGlyphs = domainN,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 1,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(1 to (domainN - 1))),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
            ),
        )
        assertTrue(
            FontTableInspector.inspect(atEdgeValid, useCache = false).passesTierAForcedOff,
            "Glyph ID numGlyphs - 1 must be read normally",
        )

        val oobGlyph = SyntheticTtfBuilder.buildTtf(
            explicitNumGlyphs = domainN,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 1,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildSingleFormat2Subtable(listOf(1 to domainN)),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
            ),
        )
        val oobInsp = FontTableInspector.inspect(oobGlyph, useCache = false)
        assertFalse(oobInsp.isStructurallyInspectable, "Glyph ID == numGlyphs must fail closed")
        assertNotNull(oobInsp.parseError)

        // 4. Deleting Multiple Substitution (Type 2 with 0 output glyphs) under Always-On fails Tier A!
        val deletingType2 = SyntheticTtfBuilder.buildTtf(
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 2,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildMultipleFormat1Subtable(listOf(1 to emptyList())),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
            ),
        )
        assertFalse(
            FontTableInspector.inspect(deletingType2, useCache = false).passesTierAForcedOff,
            "Deleting Type 2 under Always-On must fail Tier A",
        )

        // 5. Non-empty Multiple Substitution under Always-On does NOT fail Tier A!
        val expandingType2 = SyntheticTtfBuilder.buildTtf(
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 2,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildMultipleFormat1Subtable(listOf(1 to listOf(2, 3))),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("rlig", listOf(0))),
            ),
        )
        assertTrue(
            FontTableInspector.inspect(expandingType2, useCache = false).passesTierAForcedOff,
            "Non-empty Type 2 under Always-On must pass Tier A",
        )

        // 6. Nested lookup cycle (lookup 0 -> lookup 1 -> lookup 0) fails closed!
        val cyclicLookups = SyntheticTtfBuilder.buildTtf(
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 5,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildContextFormat1Subtable(
                                firstGlyph = 1,
                                remainingGlyphs = emptyList(),
                                seqLookups = listOf(SyntheticTtfBuilder.SeqLookupRec(0, 1)),
                            ),
                        ),
                    ),
                    SyntheticTtfBuilder.GsubLookupSpec(
                        lookupType = 5,
                        rawSubtables = listOf(
                            SyntheticTtfBuilder.buildContextFormat1Subtable(
                                firstGlyph = 1,
                                remainingGlyphs = emptyList(),
                                seqLookups = listOf(SyntheticTtfBuilder.SeqLookupRec(0, 0)),
                            ),
                        ),
                    ),
                ),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0))),
            ),
        )
        val cyclicInsp = FontTableInspector.inspect(cyclicLookups, useCache = false)
        assertFalse(cyclicInsp.isStructurallyInspectable, "Nested lookup cycle must fail closed")
        assertNotNull(cyclicInsp.parseError)

        // 7. Required ccmp / ss01 / liga and FeatureVariations on liga / ccmp / ss01 all fail Tier A!
        for (tag in listOf("liga", "ccmp", "ss01")) {
            val reqBytes = SyntheticTtfBuilder.buildTtf(
                gsub = SyntheticTtfBuilder.GsubSpec(
                    lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(4)),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec(tag, listOf(0))),
                    requiredFeatureIndex = 0,
                ),
            )
            assertFalse(
                FontTableInspector.inspect(reqBytes, useCache = false).passesTierAForcedOff,
                "Required '$tag' with ligature must fail Tier A",
            )

            val fvBytes = SyntheticTtfBuilder.buildTtf(
                axes = listOf(SyntheticTtfBuilder.AxisSpec("wght", 100f, 400f, 900f)),
                gsub = SyntheticTtfBuilder.GsubSpec(
                    minorVersion = 1,
                    lookups = listOf(
                        SyntheticTtfBuilder.GsubLookupSpec(1),
                        SyntheticTtfBuilder.GsubLookupSpec(4),
                    ),
                    features = listOf(SyntheticTtfBuilder.GsubFeatureSpec(tag, listOf(0))),
                    featureVariations = listOf(
                        listOf(SyntheticTtfBuilder.GsubVariationSubstitution(0, listOf(1))),
                    ),
                ),
            )
            assertFalse(
                FontTableInspector.inspect(fvBytes, useCache = false).passesTierAForcedOff,
                "FeatureVariations alternative on '$tag' with ligature must fail Tier A",
            )
        }

        // 8. Unregistered tag carrying a ligature falls to Rule 5 (Always-On) -> fails Tier A!
        val unregBytes = SyntheticTtfBuilder.buildTtf(
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = listOf(SyntheticTtfBuilder.GsubLookupSpec(4)),
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("z999", listOf(0))),
            ),
        )
        assertFalse(
            FontTableInspector.inspect(unregBytes, useCache = false).passesTierAForcedOff,
            "Unregistered tag with multi-glyph rule must fail Tier A",
        )

        // 9. Parse budget exceeded fails closed using production default limits!
        val bigCov = SyntheticTtfBuilder.buildCoverageFormat2(listOf(1..45_000))
        val bigSingle = SyntheticTtfBuilder.buildSingleFormat1Subtable(bigCov, deltaGlyphId = 1)
        val overBudgetBytes = SyntheticTtfBuilder.buildTtf(
            explicitNumGlyphs = 50_000,
            gsub = SyntheticTtfBuilder.GsubSpec(
                lookups = List(5) {
                    SyntheticTtfBuilder.GsubLookupSpec(lookupType = 1, rawSubtables = listOf(bigSingle))
                },
                features = listOf(SyntheticTtfBuilder.GsubFeatureSpec("ccmp", listOf(0, 1, 2, 3, 4))),
            ),
        )
        val overBudgetInsp = FontTableInspector.inspect(overBudgetBytes, useCache = false)
        assertFalse(overBudgetInsp.isStructurallyInspectable, "Parse budget exhaustion must fail closed")
        assertNotNull(overBudgetInsp.parseError)
    }
}
