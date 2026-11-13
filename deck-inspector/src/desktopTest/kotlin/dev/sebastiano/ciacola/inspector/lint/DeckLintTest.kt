package dev.sebastiano.ciacola.inspector.lint

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bnorm.storyboard.ContentDecorator
import dev.bnorm.storyboard.SceneFormat
import dev.bnorm.storyboard.Storyboard
import dev.bnorm.storyboard.toValue
import dev.sebastiano.ciacola.DeckMagicText
import dev.sebastiano.ciacola.DeckTextRendererProvider
import dev.sebastiano.ciacola.MagicTextV1Renderer
import dev.sebastiano.ciacola.effects.DeckWarpCodeCard
import dev.sebastiano.ciacola.effects.DottedLeaderRow
import dev.sebastiano.ciacola.effects.KeynoteStamp
import dev.sebastiano.ciacola.inspector.DeckElementOverridesFile
import dev.sebastiano.ciacola.inspector.ElementPatch
import dev.sebastiano.ciacola.inspector.LocalElementOverrides
import dev.sebastiano.ciacola.inspector.LocalStageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageInspectionRegistry
import dev.sebastiano.ciacola.inspector.StageNodeSnapshot
import dev.sebastiano.ciacola.theme.DeckThemePreset
import dev.sebastiano.ciacola.theme.DeckThemeProvider
import dev.sebastiano.ciacola.theme.DeckThemes
import dev.sebastiano.ciacola.theme.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DeckLintTest {

    private val stageFormat = SceneFormat(IntSize(1920, 1080), Density(2f))

    @Test
    fun offscreenAuditTriggersExactRulesForSyntheticSlideDefects() = runBlocking {
        val storyboard = Storyboard.build(
            title = "Synthetic Defects",
            format = stageFormat,
            decorator = ContentDecorator { content ->
                DeckThemeProvider(DeckThemes.keynoteLight()) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFFEBEBEB)) {
                        content()
                    }
                }
            },
        ) {
            // Scene 0: Overflowing text (both ellipsized box + pathological 1-char narrow wrap '1/4/s')
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Text(
                        text = "This headline is way too long for a narrow box and gets truncated abruptly",
                        style = TextStyle(fontSize = 28.sp, color = Color(0xFF242124)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        text = "1/4/s",
                        style = TextStyle(fontSize = 24.sp, color = Color(0xFF242124)),
                        modifier = Modifier.offset(y = 140.dp).width(14.dp),
                    )
                }
            }
            // Scene 1: Low contrast (< 4.5:1 on #EBEBEB)
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Text(
                        text = "Washed out copy",
                        style = TextStyle(fontSize = 18.sp, color = Color(0xFF9A9FA8)),
                    )
                }
            }
            // Scene 2: Out-of-stage bleed at x = 1980 px (990.dp at density 2f) + full-bleed exempt backdrop
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().background(Color(0xFFEBEBEB))) {
                    Text(
                        text = "Offstage headline",
                        style = TextStyle(fontSize = 28.sp, color = Color(0xFF242124)),
                        modifier = Modifier.offset(x = 990.dp, y = 100.dp),
                    )
                }
            }
            // Scene 3: Tiny 10.sp text
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Text(
                        text = "Tiny unreadable footnote",
                        style = TextStyle(fontSize = 10.sp, color = Color(0xFF242124)),
                    )
                }
            }
            // Scene 4: Overlapping headlines
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Text(
                        text = "First overlapping headline",
                        style = TextStyle(fontSize = 36.sp, color = Color(0xFF242124)),
                    )
                    Text(
                        text = "Second overlapping headline",
                        style = TextStyle(fontSize = 36.sp, color = Color(0xFF242124)),
                        modifier = Modifier.offset(y = 6.dp),
                    )
                }
            }
        }

        val controller = DeckLintController()
        val issues = controller.runFullDeckAudit(storyboard)

        val scene0Rules = issues.filter { it.sceneIndex == 0 }.map { it.ruleId }
        assertTrue(
            LintRuleId.TEXT_CLIPPED_OR_OVERFLOW in scene0Rules,
            "Expected TEXT_CLIPPED_OR_OVERFLOW in scene 0, got $issues",
        )
        assertTrue(
            issues.any { it.sceneIndex == 0 && it.ruleId == LintRuleId.TEXT_CLIPPED_OR_OVERFLOW && it.textSnippet.contains("1/4/s") },
            "Expected pathological narrow wrap to catch '1/4/s', got ${issues.filter { it.sceneIndex == 0 }}",
        )

        val scene1Rules = issues.filter { it.sceneIndex == 1 }.map { it.ruleId }
        assertTrue(
            LintRuleId.LOW_CONTRAST in scene1Rules,
            "Expected LOW_CONTRAST in scene 1, got $issues",
        )

        val scene2Rules = issues.filter { it.sceneIndex == 2 }.map { it.ruleId }
        assertTrue(
            LintRuleId.STAGE_OUT_OF_BOUNDS in scene2Rules,
            "Expected STAGE_OUT_OF_BOUNDS in scene 2, got $issues",
        )

        val scene3Rules = issues.filter { it.sceneIndex == 3 }.map { it.ruleId }
        assertTrue(
            LintRuleId.TINY_STAGE_TEXT in scene3Rules,
            "Expected TINY_STAGE_TEXT in scene 3, got $issues",
        )

        val scene4Rules = issues.filter { it.sceneIndex == 4 }.map { it.ruleId }
        assertTrue(
            LintRuleId.TEXT_OVERLAP in scene4Rules,
            "Expected TEXT_OVERLAP in scene 4, got $issues",
        )
    }

    @Test
    fun darkCodeCardInsideLightSlideDoesNotFalsePositiveOnLowContrast() = runBlocking {
        val lightTheme = DeckThemes.keynoteLight()
        val codeBg = lightTheme.colors.code.background // #2E282C
        val codeFg = lightTheme.colors.code.foreground // #EDE8E6 (light text on dark card!)

        val storyboard = Storyboard.build(
            title = "Dark Card On Light Slide",
            format = stageFormat,
            decorator = ContentDecorator { content ->
                DeckThemeProvider(lightTheme) {
                    Surface(Modifier.fillMaxSize(), color = lightTheme.colors.background) {
                        content()
                    }
                }
            },
        ) {
            scene(frames = listOf(0)) {
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                        Text(
                            text = "Light slide heading",
                            style = lightTheme.typography.heading,
                            color = lightTheme.colors.foreground,
                        )
                        Surface(
                            color = codeBg,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                text = "fun hello() = println(\"world\")",
                                style = lightTheme.typography.code,
                                color = codeFg,
                                modifier = Modifier.padding(36.dp),
                            )
                        }
                    }
                }
            }
        }

        val controller = DeckLintController()
        val issues = controller.runFullDeckAudit(storyboard)
        assertTrue(
            issues.none { it.ruleId == LintRuleId.LOW_CONTRAST },
            "Dark code card inside light slide must NOT false-positive on LOW_CONTRAST, got $issues",
        )
    }

    @Test
    fun spotlightDimmedNodeSkippedWhenOpaquePassesAndCaughtWhenOpaqueFails() {
        val registry = StageInspectionRegistry()
        // Spotlight-dimmed (0.4f < 0.45f) whose opaque white on dark passes (21:1) -> skipped!
        registry.record(
            StageNodeSnapshot(
                elementId = "spotlight-good",
                kind = "Text",
                label = "Dimmed Good",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(100f, 100f, 500f, 160f),
                resolvedText = "Dimmed spotlight text",
                effectiveForeground = Color.White,
                effectiveBackground = Color(0xFF0B0D12),
                effectiveFontSizeSp = 20f,
            ),
        )
        // Spotlight-dimmed (0.4f) whose opaque dark-gray on dark fails (~1.3:1) -> caught!
        registry.record(
            StageNodeSnapshot(
                elementId = "spotlight-bad",
                kind = "Text",
                label = "Dimmed Bad",
                sceneIndex = 0,
                beatIndex = 0,
                stageBounds = Rect(100f, 220f, 500f, 280f),
                resolvedText = "Unreadable even opaque",
                effectiveForeground = Color(0xFF22252E),
                effectiveBackground = Color(0xFF0B0D12),
                effectiveFontSizeSp = 20f,
            ),
        )

        val input = DeckLintRules.buildSlideInput(
            sceneIndex = 0,
            beatIndex = 0,
            stageSnapshots = registry.nodesForSlide(0, 0),
            semanticsTextNodes = listOf(
                LintTextNodeCandidate(
                    label = "Dimmed Good",
                    text = "Dimmed spotlight text",
                    stageBounds = Rect(100f, 100f, 500f, 160f),
                    fontSizeSp = 20f,
                    effectiveAlpha = 0.40f,
                    effectiveForeground = Color.White,
                    opaqueForeground = Color.White,
                    effectiveBackground = Color(0xFF0B0D12),
                ),
                LintTextNodeCandidate(
                    label = "Dimmed Bad",
                    text = "Unreadable even opaque",
                    stageBounds = Rect(100f, 220f, 500f, 280f),
                    fontSizeSp = 20f,
                    effectiveAlpha = 0.40f,
                    effectiveForeground = Color(0xFF22252E),
                    opaqueForeground = Color(0xFF22252E),
                    effectiveBackground = Color(0xFF0B0D12),
                ),
            ),
        )

        val issues = DeckLintRules.evaluateSlide(input)
        assertEquals(1, issues.size, "Only the node whose opaque color fails contrast should trigger: $issues")
        assertEquals("spotlight-bad", issues.single().elementId)
        assertEquals(LintRuleId.LOW_CONTRAST, issues.single().ruleId)
    }

    @Test
    fun zeroAlphaSharedElementGhostsDoNotTriggerTextOverlap() {
        val input = LintSlideInput(
            sceneIndex = 0,
            beatIndex = 0,
            textNodes = listOf(
                LintTextNodeCandidate(
                    elementId = "visible",
                    label = "Visible",
                    text = "Visible Headline",
                    stageBounds = Rect(128f, 128f, 600f, 200f),
                    fontSizeSp = 36f,
                    effectiveAlpha = 1f,
                    effectiveForeground = Color.White,
                    effectiveBackground = Color.Black,
                ),
                LintTextNodeCandidate(
                    elementId = "ghost",
                    label = "Ghost",
                    text = "Outgoing Ghost",
                    stageBounds = Rect(128f, 128f, 600f, 200f),
                    fontSizeSp = 36f,
                    effectiveAlpha = 0f,
                    effectiveForeground = Color.White,
                    effectiveBackground = Color.Black,
                ),
            ),
        )
        val issues = DeckLintRules.evaluateSlide(input)
        assertTrue(issues.isEmpty(), "Zero-alpha ghost must not trigger TEXT_OVERLAP or any rule, got $issues")
    }

    @Test
    fun quickFixElementPatchClearsLintErrorOnReAudit() = runBlocking {
        var overrides by mutableStateOf(DeckElementOverridesFile())

        fun buildStoryboard(currentOverrides: DeckElementOverridesFile): Storyboard = Storyboard.build(
            title = "Quick Fix Loop",
            format = stageFormat,
            decorator = ContentDecorator { content ->
                CompositionLocalProvider(LocalElementOverrides provides currentOverrides) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFFEBEBEB)) {
                        content()
                    }
                }
            },
        ) {
            scene(frames = listOf(0)) {
                val registry = LocalStageInspectionRegistry.current
                val patch = LocalElementOverrides.current.patchFor("s0-subtitle")
                val fg = patch?.textColorArgb?.let { Color(it) } ?: Color(0xFF9A9FA8)
                val spSize = patch?.fontSizeSp ?: 10f
                SideEffect {
                    registry?.record(
                        StageNodeSnapshot(
                            elementId = "s0-subtitle",
                            kind = "Text",
                            label = "Subtitle",
                            sceneIndex = 0,
                            beatIndex = 0,
                            stageBounds = Rect(128f, 128f, 700f, 200f),
                            resolvedText = "Editable subtitle",
                            effectiveForeground = fg,
                            effectiveBackground = Color(0xFFEBEBEB),
                            effectiveFontSizeSp = spSize,
                            isEditable = true,
                        ),
                    )
                }
                Box(Modifier.fillMaxSize().padding(64.dp)) {
                    Text(
                        text = "Editable subtitle",
                        style = TextStyle(fontSize = spSize.sp, color = fg),
                    )
                }
            }
        }

        val controller = DeckLintController()
        val initialIssues = controller.runFullDeckAudit(buildStoryboard(overrides))

        val contrastIssue = initialIssues.firstOrNull { it.ruleId == LintRuleId.LOW_CONTRAST }
        assertNotNull(contrastIssue, "Expected LOW_CONTRAST issue initially, got $initialIssues")
        val contrastFix = contrastIssue.quickFixes.firstOrNull { it.label == "Fix Contrast" }?.patch
        assertNotNull(contrastFix, "Expected deterministic Fix Contrast patch on $contrastIssue (all=$initialIssues)")

        val tinyIssue = initialIssues.firstOrNull { it.ruleId == LintRuleId.TINY_STAGE_TEXT }
        assertNotNull(tinyIssue, "Expected TINY_STAGE_TEXT issue initially, got $initialIssues")
        val sizeFix = tinyIssue.quickFixes.firstOrNull { it.label == "Bump to 16sp" }?.patch
        assertNotNull(sizeFix, "Expected deterministic Bump to 16sp patch")

        // Verify the Fix Contrast color itself passes >= 4.5:1 on #EBEBEB.
        val fixedColor = Color(contrastFix.textColorArgb!!)
        assertTrue(
            contrastRatio(fixedColor, Color(0xFFEBEBEB)) >= 4.5f,
            "Fix Contrast color must achieve >= 4.5:1",
        )

        // Apply both ElementPatch fixes and re-audit.
        val combinedPatch = ElementPatch(
            elementId = "s0-subtitle",
            textColorArgb = contrastFix.textColorArgb,
            fontSizeSp = sizeFix.fontSizeSp,
        )
        overrides = DeckElementOverridesFile(elements = mapOf("s0-subtitle" to combinedPatch))

        val afterIssues = controller.runFullDeckAudit(buildStoryboard(overrides))
        assertTrue(
            afterIssues.isEmpty(),
            "Expected all lint issues to clear after applying ElementPatch quick fixes, got $afterIssues (initial=$initialIssues)",
        )
    }

    @Test
    fun safeAreaBleedExemptsFullBleedBackdropAndFlagsNearEdgeText() {
        val input = LintSlideInput(
            sceneIndex = 0,
            beatIndex = 0,
            surfaces = listOf(
                LintSurfaceCandidate(
                    elementId = "backdrop",
                    kind = "Backdrop",
                    label = "Full Bleed",
                    stageBounds = Rect(0f, 0f, 1920f, 1080f),
                    effectiveBackground = Color(0xFF0B0D12),
                    isFullBleedSurface = true,
                ),
            ),
            textNodes = listOf(
                LintTextNodeCandidate(
                    elementId = "edge-copy",
                    label = "Edge Copy",
                    text = "Too close to top edge",
                    stageBounds = Rect(64f, 10f, 500f, 60f),
                    fontSizeSp = 24f,
                    effectiveForeground = Color.White,
                    effectiveBackground = Color(0xFF0B0D12),
                ),
            ),
        )
        val issues = DeckLintRules.evaluateSlide(input)
        assertEquals(1, issues.size)
        assertEquals(LintRuleId.SAFE_AREA_BLEED, issues.single().ruleId)
        assertEquals(LintSeverity.Warning, issues.single().severity)
        assertFalse(issues.any { it.elementId == "backdrop" })
    }

    @Test
    fun fullDeckAuditSweepsEffectsScenesWithZeroFalsePositivesUnderLatencyBudget() = runBlocking {
        fun buildCleanDeck(preset: DeckThemePreset): Storyboard {
            val theme = DeckThemes.forPreset(preset)
            return Storyboard.build(
                title = "Clean Effects Deck",
                format = stageFormat,
                decorator = ContentDecorator { content ->
                    DeckTextRendererProvider(MagicTextV1Renderer) {
                        DeckThemeProvider(theme) {
                            Surface(Modifier.fillMaxSize(), color = theme.colors.background) {
                                content()
                            }
                        }
                    }
                },
            ) {
                scene(frames = listOf("One", "Two", "Three")) {
                    Box(Modifier.fillMaxSize().padding(64.dp)) {
                        DeckMagicText(
                            transition = updateTransition(AnnotatedString(transition.targetState.toValue())),
                            style = theme.typography.body,
                        )
                    }
                }
                scene(frames = listOf(0, 1)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(64.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        DottedLeaderRow(
                            leading = "Compose",
                            trailing = "BasicText(AnnotatedString)",
                            trailingStyle = theme.typography.code.copy(fontSize = 18.sp),
                        )
                        DeckWarpCodeCard(
                            codeStates = listOf(
                                "fun demo() = Unit",
                                "fun demo(x: Int) {\n    println(x)\n}",
                            ),
                            stateTransition = updateTransition(transition.targetState.toValue()),
                        )
                        KeynoteStamp(text = "IMMUTABLE")
                    }
                }
            }
        }

        val controller = DeckLintController()
        // Warmup run (initializes Skiko font/paragraph caches once).
        controller.runFullDeckAudit(
            storyboard = buildCleanDeck(DeckThemePreset.StageDark),
            presetsToCheck = listOf(DeckThemePreset.StageDark, DeckThemePreset.KeynoteLight),
            storyboardForPreset = ::buildCleanDeck,
        )
        // Measured sweep across both theme presets (10 total beats).
        val issues = controller.runFullDeckAudit(
            storyboard = buildCleanDeck(DeckThemePreset.StageDark),
            presetsToCheck = listOf(DeckThemePreset.StageDark, DeckThemePreset.KeynoteLight),
            storyboardForPreset = ::buildCleanDeck,
        )

        assertTrue(issues.isEmpty(), "Expected 0 false positives on clean effects deck, got $issues")
        assertTrue(
            controller.lastAuditDurationMs < 1_000L,
            "Expected fast offscreen full-deck sweep, took ${controller.lastAuditDurationMs} ms",
        )
    }
}
