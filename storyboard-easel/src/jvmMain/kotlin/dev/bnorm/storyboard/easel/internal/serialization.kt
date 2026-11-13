@file:OptIn(ExperimentalSerializationApi::class)

package dev.bnorm.storyboard.easel.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*

internal object DpSizeSerializer : KSerializer<DpSize> {
    override val descriptor = buildClassSerialDescriptor("DpSize") {
        element<Float>("width")
        element<Float>("height")
    }

    override fun serialize(encoder: Encoder, value: DpSize) = encoder.encodeStructure(descriptor) {
        encodeFloatElement(descriptor, 0, value.width.value)
        encodeFloatElement(descriptor, 1, value.height.value)
    }

    override fun deserialize(decoder: Decoder): DpSize = decoder.decodeStructure(descriptor) {
        var width = -1.0f
        var height = -1.0f
        if (decodeSequentially()) {
            width = decodeFloatElement(descriptor, 0)
            height = decodeFloatElement(descriptor, 1)
        } else while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> width = decodeFloatElement(descriptor, 0)
                1 -> height = decodeFloatElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> error("Unexpected index: $index")
            }
        }
        require(width >= 0.0f && height >= 0.0f)
        DpSize(width.dp, height.dp)
    }
}

// TODO polymorphic based on subclasses of WindowPosition
internal object WindowPositionSerializer : KSerializer<WindowPosition> {
    override val descriptor = buildClassSerialDescriptor("WindowPosition") {
        element<Int>("x")
        element<Int>("y")
    }

    override fun serialize(encoder: Encoder, value: WindowPosition) = encoder.encodeStructure(descriptor) {
        encodeFloatElement(descriptor, 0, value.x.value)
        encodeFloatElement(descriptor, 1, value.y.value)
    }

    override fun deserialize(decoder: Decoder): WindowPosition = decoder.decodeStructure(descriptor) {
        var x = Float.NaN
        var y = Float.NaN
        if (decodeSequentially()) {
            x = decodeFloatElement(descriptor, 0)
            y = decodeFloatElement(descriptor, 1)
        } else while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> x = decodeFloatElement(descriptor, 0)
                1 -> y = decodeFloatElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> error("Unexpected index: $index")
            }
        }
        require(x.isFinite() && y.isFinite())
        WindowPosition(x.dp, y.dp)
    }
}

internal fun clampWindowPositionToScreens(
    position: WindowPosition,
    size: DpSize,
    screenBounds: List<Rect>,
): WindowPosition {
    if (position !is WindowPosition.Absolute || screenBounds.isEmpty()) return position
    val x = position.x.value
    val y = position.y.value
    val w = size.width.value.coerceAtLeast(64f)
    val h = size.height.value.coerceAtLeast(48f)
    val winRect = Rect(x, y, x + w, y + h)
    val minVisibleW = 64f
    val minVisibleH = 40f

    val visibleOnAnyScreen = screenBounds.any { screen ->
        val overlapW = minOf(winRect.right, screen.right) - maxOf(winRect.left, screen.left)
        val overlapH = minOf(winRect.bottom, screen.bottom) - maxOf(winRect.top, screen.top)
        overlapW >= minVisibleW && overlapH >= minVisibleH
    }
    if (visibleOnAnyScreen) return position

    val targetScreen = screenBounds.minBy { screen ->
        val dx = when {
            x < screen.left -> screen.left - x
            x > screen.right -> x - screen.right
            else -> 0f
        }
        val dy = when {
            y < screen.top -> screen.top - y
            y > screen.bottom -> y - screen.bottom
            else -> 0f
        }
        dx * dx + dy * dy
    }
    val maxLeft = (targetScreen.right - w.coerceAtMost(targetScreen.width)).coerceAtLeast(targetScreen.left)
    val maxTop = (targetScreen.bottom - h.coerceAtMost(targetScreen.height)).coerceAtLeast(targetScreen.top)
    val clampedX = x.coerceIn(targetScreen.left, maxLeft)
    val clampedY = y.coerceIn(targetScreen.top, maxTop)
    return WindowPosition(clampedX.dp, clampedY.dp)
}

private fun connectedScreenBounds(): List<Rect> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    return runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
            val b = device.defaultConfiguration?.bounds ?: return@mapNotNull null
            if (b.width > 0 && b.height > 0) {
                Rect(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
            } else {
                null
            }
        }
    }.getOrDefault(emptyList())
}

internal object WindowStateSerializer : KSerializer<WindowState> {
    override val descriptor = buildClassSerialDescriptor("WindowState") {
        element<String>("placement")
        element<Boolean>("isMinimized")
        element("position", WindowPositionSerializer.descriptor, isOptional = true)
        element("size", DpSizeSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: WindowState) = encoder.encodeStructure(descriptor) {
        encodeStringElement(descriptor, 0, value.placement.name)
        // Never persist isMinimized = true so quitting while minimized never launches invisible.
        encodeBooleanElement(descriptor, 1, false)
        if (value.position != WindowPosition.PlatformDefault) {
            encodeSerializableElement(descriptor, 2, WindowPositionSerializer, value.position)
        }
        encodeSerializableElement(descriptor, 3, DpSizeSerializer, value.size)
    }

    override fun deserialize(decoder: Decoder): WindowState = decoder.decodeStructure(descriptor) {
        var placement = WindowPlacement.Floating
        @Suppress("UNUSED_VARIABLE")
        var isMinimized = false
        var position: WindowPosition = WindowPosition.PlatformDefault
        var size = DpSize(800.dp, 600.dp)
        if (decodeSequentially()) {
            placement = WindowPlacement.valueOf(decodeStringElement(descriptor, 0))
            isMinimized = decodeBooleanElement(descriptor, 1)
            position = decodeSerializableElement(descriptor, 2, WindowPositionSerializer)
            size = decodeSerializableElement(descriptor, 3, DpSizeSerializer)
        } else while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> placement = WindowPlacement.valueOf(decodeStringElement(descriptor, 0))
                1 -> isMinimized = decodeBooleanElement(descriptor, 1)
                2 -> position = decodeSerializableElement(descriptor, 2, WindowPositionSerializer)
                3 -> size = decodeSerializableElement(descriptor, 3, DpSizeSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> error("Unexpected index: $index")
            }
        }
        val safePosition = clampWindowPositionToScreens(position, size, connectedScreenBounds())
        WindowState(placement, isMinimized = false, safePosition, size)
    }
}
