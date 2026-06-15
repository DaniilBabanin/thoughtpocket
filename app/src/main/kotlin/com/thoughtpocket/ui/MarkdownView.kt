package com.thoughtpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.thoughtpocket.ui.theme.ReachCheck
import com.thoughtpocket.ai.BULLET
import com.thoughtpocket.ai.CHECKBOX
import com.thoughtpocket.ai.HEADING
import com.thoughtpocket.ai.ORDERED
import com.thoughtpocket.ai.toggleCheckbox

/** Indent step from leading-whitespace width (2 spaces ≈ one level). */
private fun indent(ws: String) = (ws.length / 2 * 16).dp

/**
 * Render Markdown with interactive, persistent checkboxes. Bullets/ordered/headings/paragraphs are
 * read-only; ticking a `- [ ]`/`- [x]` rewrites just that line and emits the new Markdown via [onToggle].
 */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier, onToggle: (String) -> Unit) {
    val lines = markdown.split("\n")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { i, line ->
            val cb = CHECKBOX.find(line)
            val head = if (cb == null) HEADING.find(line) else null
            val ord = if (cb == null && head == null) ORDERED.find(line) else null
            val bul = if (cb == null && head == null && ord == null) BULLET.find(line) else null
            when {
                cb != null -> {
                    val (ws, mark, label) = cb.destructured
                    val checked = mark.equals("x", ignoreCase = true)
                    Row(
                        Modifier.padding(start = indent(ws), top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReachCheck(checked = checked, onToggle = {
                            onToggle(toggleCheckbox(markdown, i, !checked))
                        })
                        Spacer(Modifier.width(11.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (checked) TextDecoration.LineThrough else null,
                            color = if (checked) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                head != null -> Text(
                    head.groupValues[2],
                    style = if (head.groupValues[1].length <= 1) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ord != null -> Text(
                    "${ord.groupValues[2]}. ${ord.groupValues[3]}",
                    Modifier.padding(start = indent(ord.groupValues[1])),
                    style = MaterialTheme.typography.bodyMedium,
                )
                bul != null -> Text(
                    "•  ${bul.groupValues[2]}",
                    Modifier.padding(start = indent(bul.groupValues[1])),
                    style = MaterialTheme.typography.bodyMedium,
                )
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
