package eu.draconest.hermesbots.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Brush

/**
 * Lekki renderer Markdown dla dymków czatu (bez zależności zewnętrznych).
 * Obsługuje: **bold**, *italic*, `code`, ```bloki kodu```, ~~przekreślenie~~,
 * [linki](url), nagłówki #/##/###, listy - / 1. i cudzysłowy >.
 *
 * Streaming-friendly: parsuje to co jest — niedomknięte ** po prostu jeszcze nie
 * świeci jako bold, więc tekst "domyka się" wizualnie w trakcie streamu.
 */

private data class MdSpan(val start: Int, val end: Int, val style: SpanStyle, val link: String? = null)

/** Znajduje pary znaczników i zwraca czysty tekst + stylowane fragmenty. */
private fun parseInline(text: String): Pair<String, List<MdSpan>> {
    val spans = mutableListOf<MdSpan>()
    val out = StringBuilder()
    var i = 0

    // wzorce: (marker, dlugosc markera, styl)
    data class Rule(val marker: String, val style: SpanStyle)

    fun pushStyled(chunk: String, style: SpanStyle?, link: String? = null) {
        if (chunk.isEmpty()) return
        val start = out.length
        out.append(chunk)
        if (style != null || link != null) {
            spans.add(MdSpan(start, out.length, style ?: SpanStyle(), link))
        }
    }

    while (i < text.length) {
        when {
            // blokowy kod ``` ... ``` traktujemy jak inline (w dymku brak osobnych bloków)
            text.startsWith("```", i) -> {
                val close = text.indexOf("```", i + 3)
                val body = if (close >= 0) text.substring(i + 3, close) else text.substring(i + 3)
                // piersza linia moze byc jezykiem
                val code = body.substringAfter('\n', body)
                val st = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1A7F7F7F))
                pushStyled(code.removePrefix("\n"), st)
                i += if (close >= 0) close + 3 else text.length
            }
            text.startsWith("`", i) -> {
                val close = text.indexOf('`', i + 1)
                if (close < 0) { out.append(text[i]); i++ } else {
                    val st = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1A7F7F7F))
                    pushStyled(text.substring(i + 1, close), st)
                    i = close + 1
                }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val m = text.substring(i, i + 2)
                val close = text.indexOf(m, i + 2)
                if (close < 0) { out.append(text[i]); i++ } else {
                    pushStyled(text.substring(i + 2, close), SpanStyle(fontWeight = FontWeight.Bold))
                    i = close + 2
                }
            }
            text.startsWith("~~", i) -> {
                val close = text.indexOf("~~", i + 2)
                if (close < 0) { out.append(text[i]); i++ } else {
                    pushStyled(text.substring(i + 2, close), SpanStyle(textDecoration = TextDecoration.LineThrough))
                    i = close + 2
                }
            }
            text[i] == '*' || text[i] == '_' -> {
                val m = text[i]
                val close = text.indexOf(m, i + 1)
                if (close < 0 || close == i + 1) { out.append(text[i]); i++ } else {
                    pushStyled(text.substring(i + 1, close), SpanStyle(fontStyle = FontStyle.Italic))
                    i = close + 1
                }
            }
            text[i] == '[' -> {
                val closeBracket = text.indexOf("](", i)
                val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket + 2) else -1
                if (closeBracket in 1..closeParen && closeParen > closeBracket + 2) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    val start = out.length
                    out.append(label)
                    spans.add(MdSpan(start, out.length, SpanStyle(color = Color(0xFF66A3FF), textDecoration = TextDecoration.Underline), url))
                    i = closeParen + 1
                } else { out.append(text[i]); i++ }
            }
            else -> { out.append(text[i]); i++ }
        }
    }
    return out.toString() to spans
}

/** AnnotatedString z markdownu — do uzycia w Text()/Bubble. */
fun markdownToAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    // naglowki # na poczatku linii -> bold wiekszy wizualnie (bold + wielkie litery zostawiamy; prosciej: bold)
    val lines = text.split('\n')
    lines.forEachIndexed { li, line ->
        if (li > 0) append('\n')
        val trimmed = line.trimStart()
        val heading = when {
            trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") -> true
            else -> false
        }
        val bullet = trimmed.startsWith("- ") || Regex("^\\d+\\. ").containsMatchIn(trimmed)
        val quote = trimmed.startsWith("> ")
        val bodyLine = when {
            heading -> trimmed.dropWhile { it == '#' }.drop(1).ifBlank { "" }
            quote -> trimmed.drop(2)
            else -> line
        }
        val (clean, spans) = parseInline(bodyLine)
        val baseStyle = when {
            heading -> SpanStyle(fontWeight = FontWeight.Bold)
            bullet && trimmed.startsWith("- ") -> null
            else -> null
        }
        if (bullet) append("• ")
        val baseStart = length
        append(clean)
        baseStyle?.let { addStyle(it, baseStart, length) }
        spans.forEach { s ->
            addStyle(s.style, baseStart + s.start, baseStart + s.end)
            s.link?.let { url ->
                addStringAnnotation("URL", url, baseStart + s.start, baseStart + s.end)
            }
        }
    }
}

/**
 * Dymek z markdownem. Podczas streamingu znak kursora doklejany jest po renderze.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val annotated = remember(text) { markdownToAnnotated(text) }
    BasicText(
        annotated,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle.Default.copy(color = color)
    )
}
