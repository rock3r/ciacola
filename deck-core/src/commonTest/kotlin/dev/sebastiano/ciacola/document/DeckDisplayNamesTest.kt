package dev.sebastiano.ciacola.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckDisplayNamesTest {

    @Test
    fun slideDisplayTitleUsesTitleOrOneBasedFallback() {
        assertEquals(
            "Introduction",
            slideDisplayTitle(SlideSpec(id = "s01", title = "  Introduction  "), oneBasedIndex = 1),
        )
        assertEquals(
            "Slide 3",
            slideDisplayTitle(SlideSpec(id = "s03", title = null), oneBasedIndex = 3),
        )
        assertEquals(
            "Slide 4",
            slideDisplayTitle(SlideSpec(id = "s04", title = "   "), oneBasedIndex = 4),
        )
    }

    @Test
    fun nodeDisplayNameFormatsAllNodeKindsWithoutRawIds() {
        val shortText = NodeSpec(
            id = "s1:short",
            text = TextPayload(value = RichTextValue.Plain("Hello\n  world")),
        )
        assertEquals("Hello world", nodeDisplayName(shortText))

        val longText = NodeSpec(
            id = "s1:long",
            text = TextPayload(value = RichTextValue.Plain("A scene begins with a single clear sentence")),
        )
        val longName = nodeDisplayName(longText)
        assertTrue(longName.length <= 28, "Expected <= 28 chars, got '$longName' (${longName.length})")
        assertTrue(longName.endsWith("…"), "Expected ellipsis on truncated text, got '$longName'")

        val rect = NodeSpec(id = "s1:rect", shape = ShapePayload(geometry = ShapeGeometry.Rect))
        assertEquals("Rectangle", nodeDisplayName(rect))

        val ellipse = NodeSpec(id = "s1:oval", shape = ShapePayload(geometry = ShapeGeometry.Ellipse))
        assertEquals("Ellipse", nodeDisplayName(ellipse))

        val line = NodeSpec(id = "s1:line", shape = ShapePayload(geometry = ShapeGeometry.Line))
        assertEquals("Line", nodeDisplayName(line))

        val shader = NodeSpec(
            id = "s1:shader",
            specimen = SpecimenPayload(ref = "shaders.trippyKaleidoscope"),
        )
        assertEquals("Trippy Kaleidoscope", nodeDisplayName(shader))
        assertEquals("Custom Shader", nodeDisplayName(shader, specimenLabel = "Custom Shader"))
        assertEquals(
            "Kaleidoscope",
            nodeDisplayName(NodeSpec(id = "s1:k", specimen = SpecimenPayload(ref = "shaders.kaleidoscope"))),
        )
        assertEquals(
            "Buttons",
            nodeDisplayName(NodeSpec(id = "s1:b", specimen = SpecimenPayload(ref = "jewel.buttons"))),
        )
        assertEquals(
            "Specimen",
            nodeDisplayName(NodeSpec(id = "s1:empty", specimen = SpecimenPayload(ref = ""))),
        )

        val group = NodeSpec(id = "s1:grp", stack = ContainerPayload())
        assertEquals("Group", nodeDisplayName(group))
        assertEquals("Group", nodeTypeLabel(group))

        val populatedGroup = NodeSpec(
            id = "s1:grp2",
            stack = ContainerPayload(children = listOf(shortText, rect)),
        )
        assertEquals("Group · 2 items", nodeDisplayName(populatedGroup))

        val row = NodeSpec(id = "s1:row", row = ContainerPayload())
        assertEquals("Row", nodeDisplayName(row))
        assertEquals("Row", nodeTypeLabel(row))

        val singleRow = NodeSpec(
            id = "s1:row1",
            row = ContainerPayload(children = listOf(shortText)),
        )
        assertEquals("Row · 1 item", nodeDisplayName(singleRow))

        val col = NodeSpec(id = "s1:col", column = ContainerPayload())
        assertEquals("Column", nodeDisplayName(col))
        assertEquals("Column", nodeTypeLabel(col))

        val loupe = NodeSpec(id = "s1:loupe", loupe = LoupePayload(target = "s1:short"))
        assertEquals("Loupe", nodeDisplayName(loupe))
        assertEquals("Loupe", nodeTypeLabel(loupe))

        val code = NodeSpec(id = "s1:code", code = CodePayload(value = "val x = 1", language = "kotlin"))
        assertEquals("Code (kotlin)", nodeDisplayName(code))
    }

    @Test
    fun beatAndTrackDisplayNamesFormatBaseAndBuilds() {
        assertEquals("Base", beatDisplayName(0))
        assertEquals("Build 1", beatDisplayName(1))
        assertEquals("Build 4", beatDisplayName(4))

        val textNode = NodeSpec(
            id = "s1:prose",
            text = TextPayload(value = RichTextValue.Plain("A scene begins")),
        )
        assertEquals("Base · A scene begins", trackDisplayName(0, textNode))
        assertEquals("Build 2 · A scene begins", trackDisplayName(2, textNode))
        assertEquals("Build 1", trackDisplayName(1, null))
    }

    @Test
    fun transitionDisplayNameFormatsAllTypesAndOptionalDestination() {
        assertEquals(
            "Magic Move",
            transitionDisplayName(TransitionSpec(type = TransitionType.MagicMove)),
        )
        assertEquals(
            "Dissolve → Next Slide",
            transitionDisplayName(TransitionSpec(type = TransitionType.Dissolve), toSlideTitle = "Next Slide"),
        )
        assertEquals(
            "Fade through color",
            transitionDisplayName(TransitionSpec(type = TransitionType.FadeThroughColor)),
        )
        assertEquals("Transition", transitionDisplayName(null))
    }

    @Test
    fun curveDisplayNameMapsPresetsStandardBeziersSpringAndCustom() {
        assertEquals("Standard", curveDisplayName(Curve.Bezier(0.2, 0.0, 0.0, 1.0)))
        assertEquals("Standard", curveDisplayName(Curve.Bezier(0.4, 0.0, 0.2, 1.0)))
        assertEquals("Ease in-out", curveDisplayName(Curve.Bezier(0.42, 0.0, 0.58, 1.0)))
        assertEquals("Ease in-out", curveDisplayName(Curve.EaseInOut))
        assertEquals("Linear", curveDisplayName(Curve.Linear))
        assertEquals("Linear", curveDisplayName(Curve.Bezier(0.0, 0.0, 1.0, 1.0)))
        assertEquals("Spring", curveDisplayName(Curve.Spring(0.72, 380.0)))
        assertEquals("Custom", curveDisplayName(Curve.Bezier(0.15, 0.85, 0.35, 1.0)))
        assertEquals("Standard", curveDisplayName(null))
    }

    @Test
    fun tokenDisplayNameHumanizesCamelCaseAndDelimiters() {
        assertEquals("Elevated Surface", tokenDisplayName("elevatedSurface"))
        assertEquals("On Accent", tokenDisplayName("onAccent"))
        assertEquals("Code Background", tokenDisplayName("codeBackground"))
        assertEquals("Card Scrim", tokenDisplayName("cardScrim"))
        assertEquals("Custom Brand Color", tokenDisplayName("custom_brand-color"))
        assertEquals("Elevated Surface", DeckDisplayNames.tokenDisplayName("elevatedSurface"))
        assertEquals("", DeckDisplayNames.excerpt("Hello world", 0))
        assertEquals("", DeckDisplayNames.excerpt("Hello world", -3))
        assertEquals("H", DeckDisplayNames.excerpt("Hello world", 1))
    }
}
