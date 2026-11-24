package dev.bnorm.storyboard.text.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle

// TODO provide language specific extension support?
@Immutable
class CodeStyle private constructor(
    val simple: SpanStyle,
    val number: SpanStyle,
    val keyword: SpanStyle,
    val punctuation: SpanStyle,
    val annotation: SpanStyle,
    val comment: SpanStyle,
    val string: SpanStyle,
    val property: SpanStyle,
    val staticProperty: SpanStyle,
    val functionDeclaration: SpanStyle,
    val staticFunctionCall: SpanStyle,
    val extensionFunctionCall: SpanStyle,
    val typeParameters: SpanStyle,
    val namedArgument: SpanStyle,
    val label: SpanStyle,
    val constant: SpanStyle,
    val attribute: SpanStyle,
    val tag: SpanStyle,
    val operator: SpanStyle,
    val preprocessor: SpanStyle,
    val invalid: SpanStyle,
) {
    companion object {
        fun build(
            base: SpanStyle = SpanStyle(),
            builder: Builder.() -> Unit,
        ): CodeStyle {
            return Builder(
                base = CodeStyle(
                    simple = base,
                    number = base,
                    keyword = base,
                    punctuation = base,
                    annotation = base,
                    comment = base,
                    string = base,
                    property = base,
                    staticProperty = base,
                    functionDeclaration = base,
                    staticFunctionCall = base,
                    extensionFunctionCall = base,
                    typeParameters = base,
                    namedArgument = base,
                    label = base,
                    constant = base,
                    attribute = base,
                    tag = base,
                    operator = base,
                    preprocessor = base,
                    invalid = base,
                )
            ).apply(builder).build()
        }
    }

    class Builder internal constructor(base: CodeStyle) {
        var simple: SpanStyle = base.simple
        var number: SpanStyle = base.number
        var keyword: SpanStyle = base.keyword
        var punctuation: SpanStyle = base.punctuation
        var annotation: SpanStyle = base.annotation
        var comment: SpanStyle = base.comment
        var string: SpanStyle = base.string
        var property: SpanStyle = base.property
        var staticProperty: SpanStyle = base.staticProperty
        var functionDeclaration: SpanStyle = base.functionDeclaration
        var staticFunctionCall: SpanStyle = base.staticFunctionCall
        var extensionFunctionCall: SpanStyle = base.extensionFunctionCall
        var typeParameters: SpanStyle = base.typeParameters
        var namedArgument: SpanStyle = base.namedArgument
        var label: SpanStyle = base.label

        private var _constant: SpanStyle? = null
        var constant: SpanStyle
            get() = _constant ?: keyword
            set(value) {
                _constant = value
            }

        private var _attribute: SpanStyle? = null
        var attribute: SpanStyle
            get() = _attribute ?: annotation
            set(value) {
                _attribute = value
            }

        private var _tag: SpanStyle? = null
        var tag: SpanStyle
            get() = _tag ?: keyword
            set(value) {
                _tag = value
            }

        private var _operator: SpanStyle? = null
        var operator: SpanStyle
            get() = _operator ?: punctuation
            set(value) {
                _operator = value
            }

        private var _preprocessor: SpanStyle? = null
        var preprocessor: SpanStyle
            get() = _preprocessor ?: annotation
            set(value) {
                _preprocessor = value
            }

        private var _invalid: SpanStyle? = null
        var invalid: SpanStyle
            get() = _invalid ?: simple
            set(value) {
                _invalid = value
            }

        fun build(): CodeStyle {
            return CodeStyle(
                simple = simple,
                number = number,
                keyword = keyword,
                punctuation = punctuation,
                annotation = annotation,
                comment = comment,
                string = string,
                property = property,
                staticProperty = staticProperty,
                functionDeclaration = functionDeclaration,
                staticFunctionCall = staticFunctionCall,
                extensionFunctionCall = extensionFunctionCall,
                typeParameters = typeParameters,
                namedArgument = namedArgument,
                label = label,
                constant = constant,
                attribute = attribute,
                tag = tag,
                operator = operator,
                preprocessor = preprocessor,
                invalid = invalid,
            )
        }
    }
}
