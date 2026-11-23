package dev.bnorm.storyboard


/**
 * The canonical list of `languages` supported for parsing and rendering.
 */
sealed interface Language {
    fun scopeName(): String
}

// When adding well-known languages here. Also, add the corresponding entries to the language registry for all
// Parser implementations.

/** JavaScript Object notation. */
object Json : Language {
    override fun scopeName(): String {
        return "source.json"
    }
}

/** The Kotlin programming language. */
object Kotlin : Language {
    override fun scopeName(): String {
        return "source.kotlin"
    }
}

/** XML */
object Xml : Language {
    override fun scopeName(): String {
        return "text.xml"
    }
}

/** HTML */
object Html : Language {
    override fun scopeName(): String {
        return "text.html.basic"
    }
}

/** Cascading Style Sheets. */
object Css : Language {
    override fun scopeName(): String {
        return "source.css"
    }
}

/** JavaScript and JSX. */
object JavaScript : Language {
    override fun scopeName(): String {
        return "source.js"
    }
}

/** TypeScript and TSX. */
object TypeScript : Language {
    override fun scopeName(): String {
        return "source.ts"
    }
}

/** The Rust programming language. */
object Rust : Language {
    override fun scopeName(): String {
        return "source.rust"
    }
}

/** The Go programming language. */
object Go : Language {
    override fun scopeName(): String {
        return "source.go"
    }
}

/** The Python programming language. */
object Python : Language {
    override fun scopeName(): String {
        return "source.python"
    }
}

/** Tom's Obvious, Minimal Language. */
object Toml : Language {
    override fun scopeName(): String {
        return "source.toml"
    }
}

/** The Java programming language. */
object Java : Language {
    override fun scopeName(): String {
        return "source.java"
    }
}

/** The C programming language. */
object C : Language {
    override fun scopeName(): String {
        return "source.c"
    }
}

/** The C++ programming language. */
object Cpp : Language {
    override fun scopeName(): String {
        return "source.cpp"
    }
}

/** OpenGL Shading Language (GLSL). */
object Glsl : Language {
    override fun scopeName(): String {
        return "source.glsl"
    }
}

/** Skia Shading Language (SkSL). */
object Sksl : Language {
    override fun scopeName(): String {
        return "source.sksl"
    }
}

/** YAML Ain't Markup Language. */
object Yaml : Language {
    override fun scopeName(): String {
        return "source.yaml"
    }
}

/** Structured Query Language (SQL). */
object Sql : Language {
    override fun scopeName(): String {
        return "source.sql"
    }
}

/** POSIX sh, Bash, and Zsh shell scripts. */
object Shell : Language {
    override fun scopeName(): String {
        return "source.shell"
    }
}

/** The C# programming language. */
object CSharp : Language {
    override fun scopeName(): String {
        return "source.cs"
    }
}

/** The PHP programming language. */
object Php : Language {
    override fun scopeName(): String {
        return "source.php"
    }
}

/** A custom language that is [Parser] aware */
class Custom(val parser: Parser) : Language {
    override fun scopeName(): String {
        return "scope.custom"
    }
}
