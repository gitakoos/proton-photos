/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin runs property initialisers and `init` blocks in the order they are written. A property with a backing
 * field declared below the first `init` of its class is still null while that `init` runs, and an `init` that
 * starts a collection handled at once (a warm DataStore on the main dispatcher) reads that null and crashes the
 * screen on every start. Every class in the app keeps such properties above its first `init`.
 */
class InitOrderSourceTest {

    @Test
    fun `no property with a backing field is declared below the first init of its class`() {
        val root = File("src/main/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under ${root.absolutePath}", files.size > 100)
        val offenders = files.flatMap { file ->
            InitOrderScanner.offenders(file.readText()).map { "${file.relativeTo(root).invariantSeparatorsPath}:$it" }
        }
        assertTrue(
            "Declared below the first init of their class, so still null while it runs:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `flags initialisers and eager delegates below init, per class`() {
        val src = """
            class A(
                val ctor: Int = 1,
            ) {
                val before = 1
                init {
                    val local = 2
                }
                private var late = 3
                @Volatile private var annotated: String? = null
                val delegated by mutableStateOf(0)
                val lazyOne by lazy { 4 }
                val getter get() = 5
                val typedGetter: Int
                    get() = 6
                lateinit var noInit: String
                val wrapped: Int =
                    7
                fun f() { val inFun = 8 }
                companion object {
                    const val C = 9
                    val beforeCompanionInit = 10
                    init { }
                    val afterCompanionInit = "{" + '}'
                }
                data class Nested(
                    val p: Int = 11,
                ) {
                    val q = 12
                }
            }
        """.trimIndent()
        assertEquals(
            listOf(
                "8: A.late", "9: A.annotated", "10: A.delegated", "16: A.wrapped",
                "23: companion object.afterCompanionInit",
            ),
            InitOrderScanner.offenders(src),
        )
    }

    @Test
    fun `strings and comments do not move the class depth`() {
        val src = """
            class B {
                init { }
                // val commented = 1
                /* val blocked = 2 { */
                val s = "${'$'}{ "{" } }"
                val raw = ${"\"\"\""}{ ${'$'}{"}"} ${"\"\"\""}
            }
            class C {
                val fine = 1
                init { }
            }
        """.trimIndent()
        assertEquals(listOf("5: B.s", "6: B.raw"), InitOrderScanner.offenders(src))
    }
}

/** Finds class-level properties with a backing field declared below the first `init` of their class. */
internal object InitOrderScanner {

    private val modifiers =
        "(?:(?:private|protected|internal|public|override|open|final|abstract|lateinit|const|actual|expect)\\s+)*"
    private val property = Regex("^\\s*(?:@[\\w:.]+(?:\\([^)]*\\))?\\s+)*$modifiers(val|var)\\s+(\\w+)")
    private val initBlock = Regex("^\\s*init\\s*\\{")
    private val keyword = Regex(
        "\\b(class|object|interface|fun|val|var|init|get|set|if|else|for|while|when|try|catch|finally|do)\\b",
    )

    private class Frame(val depth: Int, val name: String?) {
        var initLine = 0
    }

    /** "line: Class.property" for each offender in [source]. */
    fun offenders(source: String): List<String> {
        val clean = blankStringsAndComments(source)
        val out = mutableListOf<String>()
        val frames = ArrayDeque<Frame>()
        var depth = 0
        var parens = 0
        var header = StringBuilder()
        for ((index, line) in clean.split('\n').withIndex()) {
            val frame = frames.lastOrNull()
            if (frame?.name != null && depth == frame.depth && parens == 0) {
                if (frame.initLine == 0 && initBlock.containsMatchIn(line)) {
                    frame.initLine = index + 1
                } else if (frame.initLine != 0) {
                    property.find(line)?.let { m ->
                        if (hasBackingField(line, m.range.last + 1, m.groupValues[0])) {
                            out += "${index + 1}: ${frame.name}.${m.groupValues[2]}"
                        }
                    }
                }
            }
            for (c in line) {
                when (c) {
                    '(', '[' -> { parens++; header.append(c) }
                    ')', ']' -> { parens--; header.append(c) }
                    '{' -> {
                        depth++
                        frames.addLast(Frame(depth, className(header.toString())))
                        header = StringBuilder()
                    }
                    '}' -> {
                        if (frames.lastOrNull()?.depth == depth) frames.removeLast()
                        depth--
                        header = StringBuilder()
                    }
                    ';' -> header = StringBuilder()
                    else -> header.append(c)
                }
            }
            header.append('\n')
        }
        // A literal the blanker misread leaves the depths off, and the rest of the file would go unchecked.
        if (depth != 0 || parens != 0) out += "0: not scanned, braces or parentheses do not balance"
        return out
    }

    private fun hasBackingField(line: String, from: Int, declared: String): Boolean {
        if (Regex("\\bconst\\s").containsMatchIn(declared)) return false
        val rest = line.substring(from)
        var nesting = 0
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '(' || c == '[' || c == '<' -> nesting++
                c == ')' || c == ']' || (c == '>' && i > 0 && rest[i - 1] != '-') -> nesting--
                nesting == 0 && c == '=' -> return true
                nesting == 0 && rest.startsWith("get(", i) && (i == 0 || !rest[i - 1].isLetterOrDigit()) -> return false
                nesting == 0 && Regex("^by\\s").containsMatchIn(rest.substring(i)) &&
                    (i == 0 || !rest[i - 1].isLetterOrDigit()) ->
                    return !Regex("^by\\s+lazy\\b").containsMatchIn(rest.substring(i))
            }
            i++
        }
        return false
    }

    /** The class whose body the `{` after [header] opens, or null when it opens anything else. */
    private fun className(header: String): String? {
        val flat = StringBuilder()
        var nesting = 0
        for (c in header) {
            when (c) {
                '(', '[' -> nesting++
                ')', ']' -> nesting--
                else -> if (nesting == 0) flat.append(c)
            }
        }
        val text = flat.toString()
        val last = keyword.findAll(text).lastOrNull() ?: return null
        val kind = last.groupValues[1]
        if (kind != "class" && kind != "object" && kind != "interface") return null
        val after = text.substring(last.range.last + 1)
        val name = Regex("^\\s*(\\w+)").find(after)?.groupValues?.get(1)
        val before = text.substring(0, last.range.first)
        return when {
            kind == "object" && Regex("\\bcompanion\\s*$").containsMatchIn(before) -> "companion object"
            name == null || (kind == "object" && after.trimStart().startsWith(":")) -> "object"
            else -> name
        }
    }

    /** [source] with every comment, string and char literal turned into spaces, line breaks kept. */
    private fun blankStringsAndComments(source: String): String = Blanker(source).run()

    private class Blanker(private val source: String) {
        private val out = StringBuilder(source)

        fun run(): String {
            code(0, template = false)
            return out.toString()
        }

        private fun blank(from: Int, to: Int) {
            for (k in from until to) if (out[k] != '\n') out[k] = ' '
        }

        /** Index just past the code that ends at an unmatched '}' (a string template) or at the end of the text. */
        private fun code(start: Int, template: Boolean): Int {
            var i = start
            var braces = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        blank(i, end)
                        i = end
                    }
                    source.startsWith("/*", i) -> {
                        var level = 0
                        var j = i
                        while (j < source.length) {
                            if (source.startsWith("/*", j)) {
                                level++
                                j += 2
                            } else if (source.startsWith("*/", j)) {
                                level--
                                j += 2
                                if (level == 0) break
                            } else {
                                j++
                            }
                        }
                        blank(i, j)
                        i = j
                    }
                    c == '"' -> {
                        val end = string(i, raw = source.startsWith("\"\"\"", i))
                        blank(i, end)
                        i = end
                    }
                    c == '\'' -> {
                        var j = i + 1
                        if (j < source.length && source[j] == '\\') j += 2
                        while (j < source.length && source[j] != '\'' && source[j] != '\n') j++
                        val end = minOf(j + 1, source.length)
                        blank(i, end)
                        i = end
                    }
                    c == '{' -> {
                        braces++
                        i++
                    }
                    c == '}' -> {
                        if (template && braces == 0) return i + 1
                        braces--
                        i++
                    }
                    else -> i++
                }
            }
            return i
        }

        /** Index just past the string literal opening at [start]. */
        private fun string(start: Int, raw: Boolean): Int {
            var i = start + if (raw) 3 else 1
            while (i < source.length) {
                when {
                    raw && source.startsWith("\"\"\"", i) -> {
                        var j = i + 3
                        while (j < source.length && source[j] == '"') j++
                        return j
                    }
                    !raw && source[i] == '\\' -> i += 2
                    !raw && source[i] == '"' -> return i + 1
                    source.startsWith("\${", i) -> i = code(i + 2, template = true)
                    else -> i++
                }
            }
            return i
        }
    }
}
