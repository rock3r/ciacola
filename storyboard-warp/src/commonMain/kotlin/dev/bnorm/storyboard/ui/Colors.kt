package dev.bnorm.storyboard.ui

import androidx.compose.ui.graphics.Color
import dev.bnorm.storyboard.State
import dev.bnorm.storyboard.Token

// Colors inspired by the IntelliJ Dark Palette

// Canvas / Surface
val Background = Color(0xFF1E1F22)

// Foreground (default text, parameters, operators)
val Foreground = Color(0xFFBCBEC4)

// Keywords (fun, val, import, class, etc.)
val Keyword = Color(0xFFCF8E6D)

// String literals
val String = Color(0xFF6AAB73)

// Comments
val Comment = Color(0xFF7A7E85)

// Numbers (123, 0xFF, etc.)
val Number = Color(0xFF2AACB8)

// Constants
val Constant = Color(0xFFCF8E6D)

// Function Calls and Definitions
val Function = Color(0xFF56A8F5)

// Classes, Interfaces and Generic Types
val Type = Color(0xFFC77DBB)

// Properties and Fields
val Property = Color(0xFFC77DBB)

// Annotations (@Composable, @Preview, etc.)
val Annotation = Color(0xFFB3AE60)

// Brackets { }, Parens ( ), Commas, Dots
val Punctuation = Color(0xFFA3A6AD)

// Syntax Errors
val Invalid = Color(0xFFFA6675)

fun Token.color(): Color {
    return when {
        scope.contains("annotation") -> Annotation
        scope.contains("attribute") -> Annotation
        scope.contains("keyword") -> Keyword
        scope.contains("storage") -> Keyword
        scope.contains("entity.name.tag") -> Keyword
        scope.contains("string") -> String
        scope.contains("comment") -> Comment
        scope.contains("constant.language") -> Constant
        scope.contains("constants.numeric") -> Number
        scope.contains("constant") -> Number
        scope.contains("function") -> Function
        scope.contains("entity.name.type") -> Type
        scope.contains("class") -> Type
        scope.contains("property") -> Property
        scope.contains("punctuation") -> Punctuation
        scope.contains("invalid") -> Invalid
        // Fallback to foreground color
        else -> Foreground
    }
}

fun State.color(): Color {
    return when (this) {
        // Should never really happen
        is State.Empty -> Foreground
        is State.Match -> current.color()
        is State.Insert -> token.color()
        is State.Delete -> token.color()
    }
}
