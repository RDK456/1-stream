package app.onestream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** `en` -> "English"; unknown codes are shown as typed. */
fun langName(code: String?): String = when {
    code.isNullOrBlank() -> "Unknown"
    code == "multi" || code == "all" -> "Multi-language"
    else -> Locale.forLanguageTag(code.replace('_', '-')).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
}

/** One option: [key] is what the caller filters on, [label] what the user sees (with a count if wanted). */
class FilterOption(val key: String, val label: String)

/** Label, then an "All" chip and one chip per option, scrolling sideways. Picking the selected chip again clears it. */
@Composable
fun FilterRow(label: String, options: List<FilterOption>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (options.size < 2) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            item { Chip("All", selected == null) { onSelect(null) } }
            items(options, key = { it.key }) { o -> Chip(o.label, selected == o.key) { onSelect(if (selected == o.key) null else o.key) } }
        }
    }
}
