package io.github.imnotstable.msc.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes a compact index of a stdlib module's public API (ADR 0066): every public type with the first sentence of its
 * Javadoc, and every public constructor, method and constant as one signature line with its own first sentence. An
 * agent greps this instead of reading sources. Read from the sources, so parameter names survive.
 */
@CacheableTask
abstract class StdlibApiIndex : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val title: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val out = StringBuilder("# ${title.get()}\n\n")
        out.append("One line per public member: `signature  // what it does`. Types are `## package.Type`.\n")
        val files = sources.asFileTree.files.filter { it.name.endsWith(".java") && it.name != "package-info.java" }
            .sortedBy { it.invariantSeparatorsPath }
        for (f in files) {
            out.append(index(f.readText()))
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(out.toString())
        }
    }

    companion object {
        private val PACKAGE = Regex("""^package\s+([\w.]+);""", RegexOption.MULTILINE)
        private val TYPE = Regex("""(@interface|\bclass|\binterface|\brecord|\benum)\s+(\w+)""")
        private val ANNOTATION = Regex("""@(?!interface\b)[\w.]+(\([^()]*(\([^()]*\)[^()]*)*\))?\s*""")

        /** {@code <T extends Record>(int a, ...)} from what follows the type name, without extends/implements. */
        fun typeParams(after: String): String {
            var s = after.trim()
            var generics = ""
            if (s.startsWith("<")) {
                var depth = 0
                var end = 0
                for ((i, ch) in s.withIndex()) {
                    if (ch == '<') depth++
                    if (ch == '>' && --depth == 0) {
                        end = i
                        break
                    }
                }
                generics = s.substring(0, end + 1)
                s = s.substring(end + 1).trim()
            }
            val components = if (s.startsWith("(")) s.substring(0, s.lastIndexOf(')') + 1) else ""
            return generics + components
        }

        /** The index entries of one source file. */
        fun index(text: String): String {
            val pkg = PACKAGE.find(text)?.groupValues?.get(1)?.substringAfter("msc.stdlib.", "") ?: ""
            val out = StringBuilder()
            Parser(text, pkg).typeBody(0, "", text.length, true, out)
            return out.toString()
        }
    }

    /** Walks declarations at type-body level, skipping method bodies and initialisers by brace depth. */
    private class Parser(val src: String, val pkg: String) {
        /** Writes this body's members, then its nested types (each with its own header), to {@code sink}. */
        fun typeBody(start: Int, outer: String, end: Int, topLevel: Boolean, sink: StringBuilder, iface: Boolean = false) {
            val own = StringBuilder()
            val nested = StringBuilder()
            typeBody(start, outer, end, topLevel, own, nested, iface)
            sink.append(own).append(nested)
        }

        fun typeBody(start: Int, outer: String, end: Int, topLevel: Boolean, own: StringBuilder, nested: StringBuilder,
                     iface: Boolean) {
            var i = start
            var doc = ""
            val decl = StringBuilder()
            while (i < end) {
                val c = src[i]
                when {
                    src.startsWith("/**", i) -> {
                        val close = src.indexOf("*/", i + 3)
                        doc = firstSentence(src.substring(i + 3, close))
                        i = close + 2
                    }
                    src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2) + 2
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) end else it }
                    c == '"' -> {
                        val q = skipString(i)
                        decl.append(src, i, q)
                        i = q
                    }
                    c == '\'' -> {
                        val q = skipChar(i)
                        decl.append(src, i, q)
                        i = q
                    }
                    c == '{' -> {
                        val close = matching(i)
                        val d = clean(decl.toString()).replace(ANNOTATION, "").trim()
                        val type = TYPE.find(d.substringBefore("("))
                        if (type != null && !d.contains("=") && !d.contains(" new ")) {
                            val name = (if (outer.isEmpty()) "" else "$outer.") + type.groupValues[2]
                            if (isPublic(d, !iface)) {
                                val header = typeParams(d.substringAfter(type.groupValues[0]))
                                nested.append("\n## ${if (pkg.isEmpty()) "" else "$pkg."}$name")
                                    .append(header)
                                    .append(if (doc.isEmpty()) "" else " — $doc").append('\n')
                                val enumConstants = if (type.groupValues[1] == "enum") enumConstants(i + 1, close) else ""
                                if (enumConstants.isNotEmpty()) {
                                    nested.append("- values: ").append(enumConstants).append('\n')
                                }
                                typeBody(if (type.groupValues[1] == "enum") enumBodyStart(i + 1, close) else i + 1,
                                    name, close, false, nested, type.groupValues[1].endsWith("interface"))
                            }
                        } else if (d.isNotEmpty() && d.contains("(") && isPublic(d, !iface)) {
                            member(own, d, doc)
                        }
                        decl.setLength(0)
                        doc = ""
                        i = close + 1
                    }
                    c == ';' -> {
                        val d = clean(decl.toString())
                        if (d.isNotEmpty() && isPublic(d, !iface) && !d.startsWith("package") && !d.startsWith("import")) {
                            member(own, d, doc, constant = d.contains(" = ") || !d.contains("("))
                        }
                        decl.setLength(0)
                        doc = ""
                        i++
                    }
                    c == '}' -> return
                    else -> {
                        decl.append(c)
                        i++
                    }
                }
            }
        }

        fun member(out: StringBuilder, d: String, doc: String, constant: Boolean = false) {
            var sig = d.replace(Regex("""@\w+(\([^)]*\))?\s*"""), "")
                .replace(Regex("""\b(public|final|default|synchronized|abstract)\s+"""), "")
                .replace(Regex("""\s+throws\s+[\w.,\s]+$"""), "").trim()
            if (sig.startsWith("record ") || sig.startsWith("class ") || sig.startsWith("interface ")) {
                return
            }
            if (constant) {
                sig = sig.substringBefore(" =").trim()
            }
            out.append("- ").append(sig).append(if (doc.isEmpty()) "" else "  // $doc").append('\n')
        }

        /** Interface members are public without saying so. */
        fun isPublic(d: String, needsKeyword: Boolean): Boolean =
            d.contains(Regex("""(^|\s)public\s""")) || (!needsKeyword && !d.contains(Regex("""(^|\s)(private|protected)\s""")))

        fun enumConstants(start: Int, end: Int): String {
            val body = src.substring(start, end)
            val firstSemi = body.indexOf(';').let { if (it < 0) body.length else it }
            return body.substring(0, firstSemi).replace(Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""\([^)]*\)"""), "").split(',').map { it.trim() }.filter { it.matches(Regex("\\w+")) }
                .joinToString(", ")
        }

        fun enumBodyStart(start: Int, end: Int): Int {
            var depth = 0
            for (i in start until end) {
                when (src[i]) {
                    '(', '{' -> depth++
                    ')', '}' -> depth--
                    ';' -> if (depth == 0) return i + 1
                }
            }
            return end
        }

        fun matching(open: Int): Int {
            var depth = 0
            var i = open
            while (i < src.length) {
                when {
                    src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2) + 1
                    src.startsWith("//", i) -> i = src.indexOf('\n', i)
                    src[i] == '"' -> i = skipString(i) - 1
                    src[i] == '\'' -> i = skipChar(i) - 1
                    src[i] == '{' -> depth++
                    src[i] == '}' -> if (--depth == 0) return i
                }
                i++
            }
            return src.length - 1
        }

        fun skipString(start: Int): Int {
            if (src.startsWith("\"\"\"", start)) {
                return src.indexOf("\"\"\"", start + 3) + 3
            }
            var i = start + 1
            while (i < src.length && src[i] != '"') {
                if (src[i] == '\\') i++
                i++
            }
            return i + 1
        }

        fun skipChar(start: Int): Int {
            var i = start + 1
            while (i < src.length && src[i] != '\'') {
                if (src[i] == '\\') i++
                i++
            }
            return i + 1
        }

        fun clean(d: String) = d.replace(Regex("""\s+"""), " ").trim()

        fun firstSentence(javadoc: String): String {
            val text = javadoc.lines().map { it.trim().removePrefix("*").trim() }
                .takeWhile { !it.startsWith("@") && !it.startsWith("<pre>") }
                .joinToString(" ").replace(Regex("""\{@(?:code|link|linkplain)\s+([^}]*)}"""), "$1")
                .replace(Regex("""<[^>]+>"""), "").replace(Regex("""\s+"""), " ").trim()
            val end = Regex("""\.(\s|$)""").find(text)?.range?.first ?: text.length
            return text.substring(0, end).take(160)
        }
    }
}
