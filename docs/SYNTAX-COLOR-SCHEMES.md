# Ciacola Syntax Color Schemes

`:syntax-highlight` includes a self-contained, zero-dependency syntax color scheme model (`dev.sebastiano.ciacola.syntax.theme`), 11 bundled color schemes derived from IntelliJ Platform and GitHub Primer, and pure-Kotlin importers for IntelliJ `.xml`/`.icls` and VS Code `.json`/`.jsonc` themes.

---

## 1. Compact Scheme Format (`*.scheme.json`, v1)

Bundled and exported schemes use **Ciacola Syntax Color Scheme v1** (`"format": "ciacola-syntax-scheme"`, `"version": 1`), a compact JSON format typically around 800–1,000 bytes per scheme.

### Top-Level Schema

| Key | Required | Type | Description |
|---|---|---|---|
| `format` | Yes | String | Must be `"ciacola-syntax-scheme"`. |
| `version` | Yes | Integer | Must be `1`. |
| `id` | Yes | String | Stable kebab-case slug (e.g. `"intellij-islands-dark"`). |
| `name` | Yes | String | Human-readable display name (e.g. `"Islands Dark"`). |
| `appearance` | Yes | String | One of `"light"`, `"dark"`, `"high-contrast-light"`, `"high-contrast-dark"`. |
| `background` | Yes | CSS Hex | Editor canvas background color (`#RRGGBB` or `#RRGGBBAA`). |
| `foreground` | Yes | CSS Hex | Default plain text foreground color (`#RRGGBB` or `#RRGGBBAA`). |
| `selectionBackground` | No | CSS Hex | Optional text selection background color. |
| `lineHighlightBackground` | No | CSS Hex | Optional caret/current line highlight background color. |
| `lineNumberForeground` | No | CSS Hex | Optional gutter line number foreground color. |
| `diffInserted` | No | CSS Hex | Optional gutter/diff inserted line accent color. |
| `diffDeleted` | No | CSS Hex | Optional gutter/diff deleted line accent color. |
| `diffChanged` | No | CSS Hex | Optional gutter/diff modified line accent color. |
| `tokens` | Yes | Object | Map from token key to a foreground shorthand or style object. |
| `source` | No | Object | Optional provenance (`family`, `upstream`, `license`, `revision`). |

### Hex Color Semantics

- Serialized colors use CSS hex notation in uppercase:
  - `#RRGGBB` when alpha is `0xFF` (opaque).
  - `#RRGGBBAA` when alpha is less than `0xFF` (for example `#EAEEF280` or `#4AC26B66`).
- `SyntaxColor.parseCssHexOrNull` accepts `#RGB`, `#RGBA`, `#RRGGBB`, and `#RRGGBBAA`.
- `SyntaxColor.parseArgbHexOrNull` accepts `#RGB`, `#ARGB`, `#RRGGBB`, and `#AARRGGBB`.
- `SyntaxColor.parseIntelliJHexOrNull` accepts 1–6 zero-left-padded hex digits (`"80"` -> `#000080`, `"33b3"` -> `#0033B3`, `"80808"` -> `#080808`) and 8-digit `RRGGBBAA` (`"FFFFFF4D"`), tolerating an optional leading `#`.

### `tokens` Entries

The `tokens` object maps lowerCamelCase `SyntaxTokenKind` keys (`plain`, `whitespace`, `keyword`, `string`, `comment`, `number`, `constant`, `function`, `type`, `property`, `annotation`, `attribute`, `tag`, `operator`, `preprocessor`, `punctuation`, `invalid`) plus optional `"functionDeclaration"` to either:

1. **Foreground-only shorthand** (string):
   ```json
   "keyword": "#CF8E6D"
   ```
   Equivalent to `{ "fg": "#CF8E6D" }` with no background and all font flags `false`.

2. **Style object** (omitting `null` and `false` fields):
   ```json
   "comment": { "fg": "#8C8C8C", "italic": true }
   ```
   Supported object fields:
   - `"fg"`: CSS hex foreground color
   - `"bg"`: CSS hex background color
   - `"bold"`: `true` when bold
   - `"italic"`: `true` when italic
   - `"underline"`: `true` when underlined
   - `"strikethrough"`: `true` when struck through

### Example

```json
{
  "format": "ciacola-syntax-scheme",
  "version": 1,
  "id": "intellij-islands-dark",
  "name": "Islands Dark",
  "appearance": "dark",
  "background": "#191A1C",
  "foreground": "#BCBEC4",
  "selectionBackground": "#214283",
  "lineHighlightBackground": "#1F2024",
  "lineNumberForeground": "#4B5059",
  "diffInserted": "#549159",
  "diffDeleted": "#868A91",
  "diffChanged": "#375FAD",
  "tokens": {
    "plain": "#BCBEC4",
    "whitespace": "#6F737A",
    "keyword": "#CF8E6D",
    "string": "#6AAB73",
    "comment": "#7A7E85",
    "number": "#2AACB8",
    "constant": { "fg": "#C77DBB", "italic": true },
    "function": "#56A8F5",
    "type": "#16BAAC",
    "property": "#C77DBB",
    "annotation": "#B3AE60",
    "attribute": "#BABABA",
    "tag": "#D5B778",
    "operator": "#BCBEC4",
    "preprocessor": "#B3AE60",
    "punctuation": "#BCBEC4",
    "invalid": "#F75464",
    "functionDeclaration": "#56A8F5"
  },
  "source": {
    "family": "IntelliJ",
    "upstream": "https://github.com/JetBrains/intellij-community",
    "license": "Apache-2.0",
    "revision": "60eb770dd920"
  }
}
```

---

## 2. Bundled Schemes (11 Total)

### IntelliJ Platform Family (Apache-2.0)

Derived from `platform/platform-resources/src/` in [`JetBrains/intellij-community`](https://github.com/JetBrains/intellij-community):

| Property / Slug | Display Name | Appearance | Upstream File |
|---|---|---|---|
| `IntelliJIslandsDark` (`intellij-islands-dark`) | Islands Dark | `dark` | `themes/islands/IslandSchemeDark.xml` |
| `IntelliJDark` (`intellij-dark`) | IntelliJ Dark | `dark` | `themes/expUI/expUI_darkScheme.xml` |
| `IntelliJLight` (`intellij-light`) | IntelliJ Light | `light` | `themes/expUI/expUI_lightScheme.xml` |
| `IntelliJDarcula` (`intellij-darcula`) | Darcula | `dark` | `DefaultColorSchemesManager.xml` (`Darcula`) |
| `IntelliJDarculaContrast` (`intellij-darcula-contrast`) | Darcula Contrast | `dark` | `themes/expUI/expUI_darculaContrastScheme.xml` |
| `IntelliJHighContrast` (`intellij-high-contrast`) | IntelliJ High Contrast | `high-contrast-dark` | `themes/islands/highContrastScheme.xml` |

### GitHub Primer Family (MIT)

Derived from [`primer/github-vscode-theme`](https://github.com/primer/github-vscode-theme) via `packages/tm-themes/themes/` in [`shikijs/textmate-grammars-themes`](https://github.com/shikijs/textmate-grammars-themes):

| Property / Slug | Display Name | Appearance | Upstream File |
|---|---|---|---|
| `GitHubLight` (`github-light`) | GitHub Light | `light` | `github-light-default.json` |
| `GitHubDark` (`github-dark`) | GitHub Dark | `dark` | `github-dark-default.json` |
| `GitHubDarkDimmed` (`github-dark-dimmed`) | GitHub Dark Dimmed | `dark` | `github-dark-dimmed.json` |
| `GitHubLightHighContrast` (`github-light-high-contrast`) | GitHub Light High Contrast | `high-contrast-light` | `github-light-high-contrast.json` |
| `GitHubDarkHighContrast` (`github-dark-high-contrast`) | GitHub Dark High Contrast | `high-contrast-dark` | `github-dark-high-contrast.json` |

---

## 3. IntelliJ XML (`.xml` / `.icls`) Inheritance & Mapping

Both `scripts/update-syntax-schemes.py` and `SyntaxSchemeImporters.importIntelliJXml` implement IntelliJ Platform's inheritance and attribute resolution semantics:

1. **Scheme inheritance (`parent_scheme`):**
   - Attributes and `<colors>` walk the `parent_scheme` chain (`scheme -> parent -> Default`) until a definition is found.
   - `SyntaxSchemeImporters.importIntelliJXml` embeds `Default` and `Darcula` base tables as well as the bundled IntelliJ schemes, and accepts custom `parentSchemes` XML maps or a `fallbackParent`.
2. **Whole-`<value>` replacement:**
   - A child `<option name="X"><value>...</value></option>` completely replaces any parent `<value>` for `X`. Parent `BACKGROUND` or `FONT_TYPE` values never leak into an overriding child `<value>`.
3. **`baseAttributes` resolution:**
   - When `<option name="X" baseAttributes="Y" />` has no `<value>` child, `Y` resolves starting from the **active** scheme first, then walking its `parent_scheme` chain, with cycle detection.
4. **Hex normalization:**
   - 1–6 hex digits are zero-left-padded to `RRGGBB` (`"80"` -> `#000080`, `"33b3"` -> `#0033B3`, `"80808"` -> `#080808`, `"627a"` -> `#00627A`).
   - 8 hex digits are parsed as `RRGGBBAA`.
5. **Font and effect attributes:**
   - `FONT_TYPE` is a bitmask: bit `0` (`1`) = bold, bit `1` (`2`) = italic (`3` = bold + italic).
   - `EFFECT_TYPE` applies when `EFFECT_COLOR` is present: `1`, `2`, `4`, `5` -> `underline = true`; `3` -> `strikethrough = true`.
6. **Exact `SyntaxTokenKind` priority mapping:**
   - `plain`: `TEXT` foreground (`bg` omitted)
   - `whitespace`: `<colors>` `WHITESPACES` -> `DEFAULT_LINE_COMMENT` -> `DEFAULT_BLOCK_COMMENT` -> `TEXT`
   - `keyword`: `DEFAULT_KEYWORD`
   - `string`: `DEFAULT_STRING`
   - `comment`: `DEFAULT_LINE_COMMENT` -> `DEFAULT_BLOCK_COMMENT` -> `DEFAULT_DOC_COMMENT`
   - `number`: `DEFAULT_NUMBER`
   - `constant`: `DEFAULT_CONSTANT` -> `DEFAULT_STATIC_FIELD`
   - `function`: `DEFAULT_FUNCTION_DECLARATION` -> `DEFAULT_INSTANCE_METHOD` -> `DEFAULT_STATIC_METHOD` -> `DEFAULT_FUNCTION_CALL`
   - `functionDeclaration`: `DEFAULT_FUNCTION_DECLARATION`
   - `type`: `DEFAULT_CLASS_NAME` (if `fg != TEXT.fg`) -> `CLASS_NAME_ATTRIBUTES` (if `fg != TEXT.fg`) -> `DEFAULT_INTERFACE_NAME` (if `fg != TEXT.fg`) -> `TYPE_PARAMETER_NAME_ATTRIBUTES` -> `DEFAULT_CLASS_NAME` -> `TEXT`
   - `property`: `DEFAULT_INSTANCE_FIELD` -> `DEFAULT_STATIC_FIELD` -> `KOTLIN_NAMED_ARGUMENT`
   - `annotation`: `ANNOTATION_NAME_ATTRIBUTES` -> `DEFAULT_METADATA`
   - `attribute`: `HTML_ATTRIBUTE_NAME` -> `XML_ATTRIBUTE_NAME` -> `DEFAULT_ATTRIBUTE`, skipping any candidate whose foreground contrast against `background` is `< 2.5` (preventing `Default`'s `#0000FF` or `High contrast`'s inherited `XML_ATTRIBUTE_NAME -> #0000FF` from leaking onto dark backgrounds while preserving Light's explicit `#174AD4`)
   - `tag`: `XML_TAG_NAME` -> `HTML_TAG_NAME` -> `DEFAULT_TAG` (foreground only, ignoring `Default`'s `#EFEFEF` background) -> `DEFAULT_KEYWORD`
   - `operator`: `DEFAULT_OPERATION_SIGN` -> `TEXT` (ignoring canvas background)
   - `preprocessor`: `OC.DIRECTIVE` -> `PREPROCESSOR_DIRECTIVE` -> `DEFAULT_METADATA` -> `DEFAULT_KEYWORD`
   - `punctuation`: `DEFAULT_BRACES` -> `DEFAULT_PARENTHS` -> `DEFAULT_OPERATION_SIGN` -> `TEXT` (ignoring canvas background)
   - `invalid`: `BAD_CHARACTER` (only when `FOREGROUND` is set, avoiding `Default`'s `#FFCCCC` background-only entry) -> `WRONG_REFERENCES_ATTRIBUTES` -> `CONSOLE_ERROR_OUTPUT` -> `LOG_ERROR_OUTPUT` -> `ERRORS_ATTRIBUTES`
   - Chrome colors: `SELECTION_BACKGROUND`, `CARET_ROW_COLOR`, `LINE_NUMBERS_COLOR`, `ADDED_LINES_COLOR`, `DELETED_LINES_COLOR`, `MODIFIED_LINES_COLOR`.

---

## 4. VS Code Theme JSON / JSONC Imports

Both `scripts/update-syntax-schemes.py` and `SyntaxSchemeImporters.importVsCodeJson` support standard VS Code themes:

1. **JSONC support (Kotlin importer):**
   - `SyntaxSchemeImporters.importVsCodeJson` uses a pure-Kotlin O(N) JSONC parser supporting `//` line comments, `/* */` block comments, trailing commas, and full JSON string escapes (including `\uXXXX`), with a 128-level nesting depth limit. (The Python updater script strips JSONC comments and trailing commas via `strip_jsonc()` and parses the resulting JSON with Python's `json.loads`.)
2. **`include` chaining (Kotlin importer):**
   - `SyntaxSchemeImporters.importVsCodeJson` (and `importAuto` when supplied an `includeResolver`) resolves parent themes recursively with cycle detection (up to depth 32); child `colors` and `semanticTokenColors` override parent entries, and child `tokenColors` append after parent rules. (Standalone single-file themes in `scripts/update-syntax-schemes.py` do not resolve `include`.)
3. **Appearance detection:**
   - Inspects `type` (`"light"`, `"dark"`, `"hcLight"`, `"hcDark"`, `"hc"`, `"hc-black"`, `"hc-light"`) and `"high contrast"` / `"high-contrast"` in the theme name, falling back to `background.relativeLuminance() >= 0.5`.
4. **Specificity-weighted TextMate matching & `semanticTokenColors` fallback:**
   - Supports string comma-separated and array `scope` selectors.
   - Matches exact scopes or dot-separated prefixes (e.g. `constant` matches `constant.numeric`), ranking by scope segment depth (`scope.count('.') + 1`) and breaking ties in favor of later rules in `tokenColors`.
   - Falls back to `semanticTokenColors` when no `tokenColors` rule matches a token kind.
5. **Chrome colors:**
   - `editor.background`, `editor.foreground`, `editor.selectionBackground`, `editor.lineHighlightBackground`, `editorLineNumber.foreground`, `editorWhitespace.foreground`, `editorGutter.addedBackground` / `diffEditor.insertedTextBackground`, `editorGutter.deletedBackground` / `diffEditor.removedTextBackground`, and `editorGutter.modifiedBackground`.

---

## 5. Running `scripts/update-syntax-schemes.py`

The Python 3 stdlib-only updater script regenerates or verifies the 11 checked-in `*.scheme.json` files in `syntax-highlight/src/commonMain/schemes/`:

```bash
# Regenerate all 11 schemes from pinned upstream commits (JetBrains/intellij-community + shikijs/textmate-grammars-themes)
python3 scripts/update-syntax-schemes.py

# Use a local intellij-community checkout (records 12-char git SHA, never local paths)
python3 scripts/update-syntax-schemes.py --ijpl-dir /path/to/intellij-community

# Pin specific upstream Git refs or use a local VS Code themes directory
python3 scripts/update-syntax-schemes.py --ijpl-ref master --github-ref main
python3 scripts/update-syntax-schemes.py --source github --github-dir /path/to/themes

# Verify checked-in *.scheme.json files match pinned upstream sources (non-zero exit on mismatch)
python3 scripts/update-syntax-schemes.py --check
```

---

## 6. Kotlin API Usage

```kotlin
import dev.sebastiano.ciacola.syntax.SyntaxLanguage
import dev.sebastiano.ciacola.syntax.SyntaxHighlighter
import dev.sebastiano.ciacola.syntax.theme.SyntaxColor
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorScheme
import dev.sebastiano.ciacola.syntax.theme.SyntaxColorSchemes
import dev.sebastiano.ciacola.syntax.theme.SyntaxSchemeImporters

// 1. Access bundled schemes
val darkScheme = SyntaxColorSchemes.IntelliJIslandsDark
val lightScheme = SyntaxColorSchemes.defaultFor(isLight = true)
val ghDimmed = SyntaxColorSchemes.findById("github-dark-dimmed")!!

// 2. Style tokens from SyntaxHighlighter
val tokens = SyntaxHighlighter.lex("fun main() = println(\"hi\")", SyntaxLanguage.Kotlin)
for (token in tokens) {
    val style = darkScheme.styleFor(token.kind)
    val fgArgb = (style.foreground ?: darkScheme.foreground).argb
    // Apply fgArgb, style.bold, style.italic, etc.
}

// 3. Round-trip compact JSON
val compactJson = darkScheme.toCompactJson()
val prettyJson = darkScheme.toCompactJson(pretty = true)
val restored = SyntaxColorScheme.fromCompactJson(compactJson)

// 4. Import external IntelliJ XML or VS Code JSON/JSONC
val importedXml = SyntaxSchemeImporters.importIntelliJXml(xmlString)
val importedVsCode = SyntaxSchemeImporters.importVsCodeJson(jsoncString)
val autoImported = SyntaxSchemeImporters.importAuto(
    content = rawFileContent,
    fileName = "custom.icls",
    includeResolver = { relPath -> loadRelativeFile(relPath) },
)

// 5. Color utilities
val accent = SyntaxColor.parseCssHexOrNull("#56A8F5")!!
val contrast = accent.contrastRatio(darkScheme.background)
```
