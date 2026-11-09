package com.digitronics.chat.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.digitronics.chat.R
import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.ui.theme.ToneColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bot report cards (`systemBlocks`, notificationBlockSchema in the server's
 * schemas.ts). Rendered here: header, lead, callout, stats, list, table, note.
 * The contract is skip-unknown with `body` always complete, so if any block is
 * of a type this build does not draw, the plain body is shown as well — nothing
 * a bot said is ever hidden.
 */
@Composable
fun ReportCard(title: String?, severity: String?, body: String, blocksJson: String?) {
    val blocks = remember(blocksJson) { parseBlocks(blocksJson) }
    val stripe = when (severity) {
        "critical" -> ToneColors.critical
        "warn" -> ToneColors.warn
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!title.isNullOrBlank()) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                var unknown = blocks.isEmpty()
                blocks.forEach { block ->
                    if (!renderBlock(block)) unknown = true
                }
                if (unknown && body.isNotBlank()) {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Draws one block; returns false for a type this build does not know. */
@Composable
private fun renderBlock(block: JsonObject): Boolean {
    when (block.str("type")) {
        "header" -> Text(block.str("text").orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        "lead" -> Text(marks(block.str("text").orEmpty()), style = MaterialTheme.typography.bodyLarge)
        "note" -> Text(marks(block.str("text").orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        "callout" -> Callout(block)
        "stats" -> Stats(block)
        "list" -> ListBlock(block)
        "table" -> Table(block)
        else -> return false
    }
    return true
}

@Composable
private fun Callout(block: JsonObject) {
    val tone = toneColor(block.str("tone"))
    Surface(shape = RoundedCornerShape(10.dp), color = (tone ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.10f)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            block.str("label")?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = tone ?: MaterialTheme.colorScheme.primary)
            }
            Text(marks(block.str("text").orEmpty()), style = MaterialTheme.typography.bodyMedium)
            block.str("confidence")?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stats(block: JsonObject) {
    val items = block.objects("items")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.str("title")?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        if (block.str("layout") == "tiles") {
            items.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(item.str("label").orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                Text(
                                    valueWithUnit(item),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = toneColor(item.str("tone")) ?: MaterialTheme.colorScheme.onSurface,
                                )
                                Delta(item)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(item.str("label").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        item.str("note")?.let { Text(marks(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            valueWithUnit(item),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = toneColor(item.str("tone")) ?: MaterialTheme.colorScheme.onSurface,
                        )
                        Delta(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun Delta(item: JsonObject) {
    val delta = item["delta"] as? JsonObject ?: return
    val arrow = if (delta.str("dir") == "down") "↓" else "↑"
    // A change is drawn neutral: the arrow gives the direction, never good/bad.
    Text("$arrow ${delta.str("text").orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ListBlock(block: JsonObject) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.str("title")?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        block.objects("items").forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .padding(top = 6.dp, end = 8.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(toneColor(item.str("tone")) ?: MaterialTheme.colorScheme.primary),
                )
                Column(Modifier.weight(1f)) {
                    Row {
                        item.str("tag")?.let {
                            Text("$it · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(marks(item.str("title").orEmpty()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        item.str("value")?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item.str("detail")?.let {
                        Text(marks(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Table(block: JsonObject) {
    val columns = block.objects("columns")
    val rows = block.objects("rows")
    Column {
        block.str("title")?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
            Row {
                columns.forEach { col ->
                    TableCell(col.str("label").orEmpty(), col.str("align"), header = true, tone = null)
                }
            }
            rows.forEach { row ->
                Row {
                    row.objects("cells").forEachIndexed { i, cell ->
                        TableCell(cell.str("text").orEmpty(), columns.getOrNull(i)?.str("align"), header = false, tone = toneColor(cell.str("tone")))
                    }
                }
            }
        }
        val omitted = (block["rowsOmitted"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (omitted > 0) {
            Text(
                pluralStringResource(R.plurals.report_rows_omitted, omitted, omitted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TableCell(text: String, align: String?, header: Boolean, tone: Color?) {
    Text(
        text,
        modifier = Modifier.widthIn(min = 72.dp, max = 180.dp).padding(horizontal = 6.dp, vertical = 3.dp),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = tone ?: MaterialTheme.colorScheme.onSurface,
        textAlign = if (align == "end") TextAlign.End else TextAlign.Start,
    )
}

private fun valueWithUnit(item: JsonObject): String =
    listOfNotNull(item.str("value"), item.str("unit")).joinToString(" ")

private fun toneColor(tone: String?): Color? = when (tone) {
    "good" -> ToneColors.good
    "warn" -> ToneColors.warn
    "critical" -> ToneColors.critical
    else -> null
}

internal fun parseBlocks(json: String?): List<JsonObject> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { ApiJson.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { it as? JsonObject }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private val MARK = Regex("""(?<![\p{L}\p{N}])(\*[^*\s](?:[^*]*[^*\s])?\*|__[^_\s](?:[^_]*[^_\s])?__|_[^_\s](?:[^_]*[^_\s])?_|~[^~\s](?:[^~]*[^~\s])?~)(?![\p{L}\p{N}])""")

/** The chat's inline marks: *fact* bold, _guess_ italic, __deadline__ underline, ~replaced~ strike. */
internal fun marks(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in MARK.findAll(text)) {
        append(text.substring(last, match.range.first))
        val token = match.value
        val (inner, style) = when {
            token.startsWith("__") -> token.substring(2, token.length - 2) to SpanStyle(textDecoration = TextDecoration.Underline)
            token.startsWith("*") -> token.substring(1, token.length - 1) to SpanStyle(fontWeight = FontWeight.Bold)
            token.startsWith("_") -> token.substring(1, token.length - 1) to SpanStyle(fontStyle = FontStyle.Italic)
            else -> token.substring(1, token.length - 1) to SpanStyle(textDecoration = TextDecoration.LineThrough)
        }
        withStyle(style) { append(inner) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
