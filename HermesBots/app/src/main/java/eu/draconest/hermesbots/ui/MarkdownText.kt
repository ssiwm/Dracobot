package eu.draconest.hermesbots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renderer Markdown poziom BLOKOWY dla dymków czatu (bez zależności zewnętrznych).
 *
 * Bloki: ```kod``` (przewijany w poziomie, zaznaczalny), tabele |a|b|,
 * nagłówki #/##/###, listy -/1., cytaty >, akapity.
 * Inline: **bold**, *italic*, `code`, ~~strike~~, [linki](url).
 *
 * Streaming-friendly: parsuje to co jest — niedomknięte znaczniki nie psują layoutu.
 */

private val CodeBg = Color(0x227F7F7F)
private val LinkColor = Color(0xFF66A3FF)
private val QuoteBar = Color(0x558F8F8F)

/** Znajduje pary znaczników inline i zwraca czysty tekst + stylowane fragmenty. */
private fun parseInline(text: String): Pair<String, List<Triple<Int, Int, SpanStyle>>> {
    val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    val out = StringBuilder()
    var i = 0

    fun push(chunk: String, style: SpanStyle?) {
        if (chunk.isEmpty()) return
        val start = out.length
        out.append(chunk)
        if (style != null) spans.add(Triple(start, out.length, style))
    }

    while (i < text.length) {
        when {
            text.startsWith("```", i) -> {
                val close = text.indexOf("```", i + 3)
                val body = if (close >= 0) text.substring(i + 3, close) else text.substring(i + 3)
                push(body.substringAfter('\n', body).removePrefix("\n"),
                    SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBg))
                i += if (close >= 0) close + 3 else text.length
            }
            text.startsWith("`", i) -> {
                val close = text.indexOf('`', i + 1)
                if (close < 0) { out.append(text[i]); i++ } else {
                    push(text.substring(i + 1, close),
                        SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBg))
                    i = close + 1
                }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val m = text.substring(i, i + 2)
                val close = text.indexOf(m, i + 2)
                if (close < 0) { out.append(text[i]); i++ } else {
                    push(text.substring(i + 2, close), SpanStyle(fontWeight = FontWeight.Bold))
                    i = close + 2
                }
            }
            text.startsWith("~~", i) -> {
                val close = text.indexOf("~~", i + 2)
                if (close < 0) { out.append(text[i]); i++ } else {
                    push(text.substring(i + 2, close), SpanStyle(textDecoration = TextDecoration.LineThrough))
                    i = close + 2
                }
            }
            text[i] == '*' || text[i] == '_' -> {
                val m = text[i]
                val close = text.indexOf(m, i + 1)
                if (close < 0 || close == i + 1) { out.append(text[i]); i++ } else {
                    push(text.substring(i + 1, close), SpanStyle(fontStyle = FontStyle.Italic))
                    i = close + 1
                }
            }
            text[i] == '[' -> {
                val cb = text.indexOf("](", i)
                val cp = if (cb >= 0) text.indexOf(')', cb + 2) else -1
                if (cb in 1 until cp) {
                    val label = text.substring(i + 1, cb)
                    val url = text.substring(cb + 2, cp)
                    val start = out.length
                    out.append(label)
                    spans.add(Triple(start, out.length,
                        SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline)))
                    // URL w adnotacji — BasicText z linkiem nie jest klikalny bez handlera,
                    // ale long-press kopiuje caly tekst i tak.
                    i = cp + 1
                } else { out.append(text[i]); i++ }
            }
            else -> { out.append(text[i]); i++ }
        }
    }
    return out.toString() to spans
}

/** AnnotatedString z linii z markdownem inline. */
private fun inlineAnnotated(line: String, base: SpanStyle? = null): AnnotatedString = buildAnnotatedString {
    val (clean, spans) = parseInline(line)
    val s0 = length
    append(clean)
    base?.let { addStyle(it, s0, length) }
    spans.forEach { (a, b, st) -> addStyle(st, s0 + a, s0 + b) }
}

private fun isTableLine(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.length > 2
}

private fun isSeparatorLine(line: String): Boolean =
    Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$").matches(line) && line.contains('-')

private fun tableCells(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/**
 * Pelna tresc wiadomosci jako zestaw blokow. Uzywaj wewnatrz kolumny dymka.
 */
@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier, textColor: Color) {
    val blocks = remember(text) { splitBlocks(text) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> {
                    SelectionContainer {
                        BasicText(
                            inlineAnnotated(block.text),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = textColor
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CodeBg, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Heading -> {
                    BasicText(
                        inlineAnnotated(block.text, SpanStyle(fontWeight = FontWeight.Bold)),
                        style = TextStyle(
                            fontSize = when (block.level) {
                                1 -> 19.sp; 2 -> 17.sp; else -> 15.5.sp
                            },
                            lineHeight = 22.sp,
                            color = textColor
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Table -> {
                    // prosta siatka: naglowek bold, linie pod spodem; przewijana w poziomie
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(CodeBg.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        block.rows.forEachIndexed { ri, row ->
                            Row {
                                row.forEach { cell ->
                                    BasicText(
                                        inlineAnnotated(cell, if (ri == 0) SpanStyle(fontWeight = FontWeight.Bold) else null),
                                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = textColor),
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(150.dp).padding(3.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.ListItem -> {
                    Row {
                        BasicText(
                            if (block.ordered) "${block.number}. " else "•  ",
                            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = textColor)
                        )
                        BasicText(
                            inlineAnnotated(block.text),
                            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = textColor)
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(QuoteBar)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            inlineAnnotated(block.text, SpanStyle(fontStyle = FontStyle.Italic)),
                            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = textColor.copy(alpha = 0.9f))
                        )
                    }
                }
                is MdBlock.Paragraph -> {
                    BasicText(
                        inlineAnnotated(block.text),
                        style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = textColor)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val text: String, val level: Int) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    data class ListItem(val text: String, val ordered: Boolean, val number: Int = 0) : MdBlock()
    data class Quote(val text: String) : MdBlock()
}

/** Dzieli tekst na bloki (kod/tabela/naglowek/lista/cytat/akapit). */
private fun splitBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.split('\n')
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Paragraph(para.toString().trim()))
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i]); i++
                }
                i++ // zjedz zamykajacy ```
                blocks.add(MdBlock.Code(body.toString().trimEnd('\n')))
            }
            isTableLine(line) -> {
                flushPara()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableLine(lines[i])) {
                    if (!isSeparatorLine(lines[i])) rows.add(tableCells(lines[i]))
                    i++
                }
                if (rows.isNotEmpty()) blocks.add(MdBlock.Table(rows))
            }
            Regex("^#{1,3} ").containsMatchIn(trimmed) -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Heading(trimmed.dropWhile { it == '#' }.trim(), level))
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || Regex("^\\d+\\. ").containsMatchIn(trimmed) -> {
                flushPara()
                val ordered = Regex("^\\d+\\. ").containsMatchIn(trimmed)
                val number = if (ordered) trimmed.substringBefore('.').toIntOrNull() ?: 1 else 0
                val content = if (ordered) trimmed.substringAfter(". ") else trimmed.drop(2)
                blocks.add(MdBlock.ListItem(content, ordered, number))
                i++
            }
            trimmed.startsWith("> ") -> {
                flushPara()
                blocks.add(MdBlock.Quote(trimmed.drop(2)))
                i++
            }
            trimmed.isEmpty() -> {
                flushPara()
                i++
            }
            else -> {
                para.append(line).append('\n')
                i++
            }
        }
    }
    flushPara()
    return blocks
}

/**
 * Kompatybilnosc: pojedynczy AnnotatedString (uzywany w miejscach bez blokow).
 */
fun markdownToAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { li, line ->
        if (li > 0) append('\n')
        val (clean, spans) = parseInline(line)
        val s0 = length
        append(clean)
        spans.forEach { (a, b, st) -> addStyle(st, s0 + a, s0 + b) }
    }
}
