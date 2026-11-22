package dev.sebastiano.ciacola.replications.jewel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.sebastiano.ciacola.replications.njpt.NjptGeminiDarkMarkdownChatSpecimen
import dev.sebastiano.ciacola.replications.njpt.NjptJewelMarkdownSplitDemoSpecimen

/**
 * Metadata record describing one live Jewel or NJPT slide specimen emitted by `kpf_to_deck_json.py`.
 */
data class JewelSpecimenDescriptor(
    val ref: String,
    val label: String,
    val periodMs: Long,
    val isDark: Boolean,
)

/**
 * Complete catalog of all 22 live Jewel (`20`) and Not Just Plain Text (`2`) UI specimens,
 * matching the exact `ref` strings and `periodMs` values emitted by the KPF converter.
 */
val ALL_JEWEL_AND_NJPT_SPECIMENS: List<JewelSpecimenDescriptor> = listOf(
    JewelSpecimenDescriptor("jewel.taskBasedProfilers", "Task-Based Profilers Home", 0L, isDark = false),
    JewelSpecimenDescriptor("jewel.addDeviceDialog", "Add Device Wizard Dialog", 12_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiAuthCard", "Gemini Sign-In Card", 4_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiPromptInput", "Gemini Prompt Input", 12_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiStreamingChat", "Gemini Streaming Chat", 10_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiChatComposer", "Gemini Chat Composer", 8_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.referencesBouncyExpand", "References Bouncy Expand v1", 3_500L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiToolWindowFull", "Gemini Tool Window Full", 6_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.confettiPartyPopper", "Confetti Party Popper", 1_400L, isDark = false),
    JewelSpecimenDescriptor("jewel.markdownStandaloneSample", "Jewel Markdown Standalone Sample", 14_000L, isDark = false),
    JewelSpecimenDescriptor("jewel.swingComposeInterop", "Swing + Jewel Compose Interop", 0L, isDark = false),
    JewelSpecimenDescriptor("jewel.microinteractionScrollFab", "Microinteraction: Scroll-to-Bottom FAB", 8_500L, isDark = false),
    JewelSpecimenDescriptor("jewel.microinteractionEnabledButton", "Microinteraction: Enabled Button", 2_400L, isDark = false),
    JewelSpecimenDescriptor("jewel.adaptiveDemoWizard", "Adaptive Jewel Demo Wizard", 5_400L, isDark = false),
    JewelSpecimenDescriptor("jewel.adaptiveWireframePanes", "Adaptive Wireframe Panes", 6_400L, isDark = true),
    JewelSpecimenDescriptor("jewel.m3ExpressiveShowcase", "Material 3 Expressive Showcase", 9_400L, isDark = false),
    JewelSpecimenDescriptor("jewel.springTokenTree", "Spring Motion Token Tree", 0L, isDark = false),
    JewelSpecimenDescriptor("jewel.geminiLoadingChoreography", "Gemini Loading Choreography", 2_400L, isDark = false),
    JewelSpecimenDescriptor("jewel.referencesBouncySpringV2", "References Bouncy Spring v2", 2_200L, isDark = false),
    JewelSpecimenDescriptor("jewel.cohesionIdeShowcase", "Cohesive IDE & Emulator Showcase", 0L, isDark = true),
    JewelSpecimenDescriptor("njpt.jewelMarkdownSplitDemo", "NJPT Jewel Markdown Split Demo", 14_000L, isDark = false),
    JewelSpecimenDescriptor("njpt.geminiDarkMarkdownChat", "NJPT Gemini Dark Markdown Chat", 12_000L, isDark = true),
)

/**
 * Unified dispatcher mounting any of the 22 `jewel.*` / `njpt.*` specimen refs with typed
 * converter parameters (`mirrored`, `widthFraction`, `panel`) and deterministic [loopMillis].
 */
@Composable
fun RenderJewelOrNjptSpecimen(
    ref: String,
    loopMillis: Long = 0L,
    params: Map<String, Any?> = emptyMap(),
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when (ref) {
        "jewel.taskBasedProfilers" -> TaskBasedProfilersSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.addDeviceDialog" -> AddDeviceDialogSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiAuthCard" -> GeminiAuthCardSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiPromptInput" -> GeminiPromptInputSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiStreamingChat" -> GeminiStreamingChatSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiChatComposer" -> GeminiChatComposerSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.referencesBouncyExpand" -> ReferencesBouncyExpandSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiToolWindowFull" -> GeminiToolWindowFullSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.confettiPartyPopper" -> {
            val mirrored = (params["mirrored"] as? Boolean)
                ?: params["mirrored"]?.toString()?.toBooleanStrictOrNull()
                ?: false
            ConfettiPartyPopperSpecimen(
                loopMillis = loopMillis,
                mirrored = mirrored,
                isInteractive = isInteractive,
                modifier = modifier,
            )
        }
        "jewel.markdownStandaloneSample" -> MarkdownStandaloneSampleSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.swingComposeInterop" -> SwingComposeInteropSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.microinteractionScrollFab" -> MicrointeractionScrollFabSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.microinteractionEnabledButton" -> MicrointeractionEnabledButtonSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.adaptiveDemoWizard" -> AdaptiveDemoWizardSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.adaptiveWireframePanes" -> {
            val widthFraction = (params["widthFraction"] as? Number)?.toFloat()
                ?: params["widthFraction"]?.toString()?.toFloatOrNull()
                ?: 0.72f
            AdaptiveWireframePanesSpecimen(
                loopMillis = loopMillis,
                widthFraction = widthFraction,
                isInteractive = isInteractive,
                modifier = modifier,
            )
        }
        "jewel.m3ExpressiveShowcase" -> M3ExpressiveShowcaseSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.springTokenTree" -> SpringTokenTreeSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.geminiLoadingChoreography" -> GeminiLoadingChoreographySpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.referencesBouncySpringV2" -> ReferencesBouncySpringV2Specimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "jewel.cohesionIdeShowcase" -> {
            val panel = params["panel"]?.toString() ?: "ideFull"
            CohesionIdeShowcaseSpecimen(
                panel = panel,
                loopMillis = loopMillis,
                isInteractive = isInteractive,
                modifier = modifier,
            )
        }
        "njpt.jewelMarkdownSplitDemo" -> NjptJewelMarkdownSplitDemoSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        "njpt.geminiDarkMarkdownChat" -> NjptGeminiDarkMarkdownChatSpecimen(
            loopMillis = loopMillis,
            isInteractive = isInteractive,
            modifier = modifier,
        )
        else -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Unknown specimen ref: $ref")
        }
    }
}
