package com.thoughtpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.thoughtpocket.ui.theme.ReachCheck
import com.thoughtpocket.ai.MdLine
import com.thoughtpocket.ai.parseMarkdown
import com.thoughtpocket.ai.toggleCheckbox

/** Indent step from leading-whitespace width (2 spaces ≈ one level). */
private fun indent(ws: String) = (ws.length / 2 * 16).dp

/**
 * Render Markdown with interactive, persistent checkboxes. Bullets/ordered/headings/paragraphs are
 * read-only; ticking a `- [ ]`/`- [x]` rewrites just that line and emits the new Markdown via [onToggle].
 * Parsing is cached per distinct string — the detail screen recomposes every live tick while recording,
 * and a toggle produces a NEW markdown string, so the [remember] key invalidates exactly when needed.
 */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier, onToggle: (String) -> Unit) {
    val lines = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { i, line ->
            when (line) {
                is MdLine.Checkbox -> Row(
                    Modifier.padding(start = indent(line.indent), top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReachCheck(checked = line.checked, onToggle = {
                        onToggle(toggleCheckbox(markdown, i, !line.checked))
                    })
                    Spacer(Modifier.width(11.dp))
                    Text(
                        line.label,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                        color = if (line.checked) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                is MdLine.Heading -> Text(
                    line.text,
                    style = if (line.level <= 1) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                is MdLine.Ordered -> Text(
                    "${line.number}. ${line.text}",
                    Modifier.padding(start = indent(line.indent)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MdLine.Bullet -> Text(
                    "•  ${line.text}",
                    Modifier.padding(start = indent(line.indent)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MdLine.Blank -> Spacer(Modifier.height(4.dp))
                is MdLine.Plain -> Text(line.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
