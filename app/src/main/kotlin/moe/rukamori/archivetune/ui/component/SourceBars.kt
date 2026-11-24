package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.InstalledModule

/**
 * Shared source controls for every screen that searches custom sources.
 *
 * These live here on purpose: the workbench, the playlist "add songs" page and the
 * change-source dialog must be visually identical, so they all render the same picker row, the
 * same source sheet and the same per-source tab row instead of three lookalike copies.
 */

/** `名称 · 作者 v版本` so two installs of the same source are distinguishable. */
fun sourceChipLabel(module: InstalledModule): String =
    buildString {
        append(module.manifest.name)
        module.manifest.author?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        append(" v${module.manifest.version}")
    }

/**
 * One line: (optional back) + source picker button + query field + search button.
 *
 * A horizontal chip strip does not scale past a couple of sources (it hides the rest off screen
 * and needs a scroll to answer "what is selected"), so the selection moved into a sheet and the
 * row only shows how much is selected.
 */
@Composable
fun SourcePickerRow(
    selectedCount: Int,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    onOpenPicker: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        OutlinedButton(
            onClick = onOpenPicker,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.source_workbench_picker, selectedCount, totalCount),
                maxLines = 1,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.search_yt_music), maxLines = 1) },
            singleLine = true,
        )
        Button(onClick = onSearch, enabled = query.isNotBlank() && !searching && selectedCount > 0) {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.source_workbench_search))
            }
        }
    }
}

/** Source list with multi-select plus select-all / invert / clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    modules: List<InstalledModule>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.source_workbench_sources_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.source_workbench_select_all_sources)) }
                TextButton(onClick = onInvert) { Text(stringResource(R.string.source_workbench_invert_sources)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.source_workbench_clear_sources)) }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(modules, key = { module -> module.id }) { module ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(module.id) }
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = module.id in selectedIds, onCheckedChange = { onToggle(module.id) })
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(module.manifest.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = sourceChipLabel(module).removePrefix(module.manifest.name).trim().trimStart('·').trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.source_workbench_done))
            }
        }
    }
}

/**
 * One tab per searched source: paging belongs to a tab, so several sources never look like one
 * long stitched list. Labels carry the result count so a page load is visible.
 */
@Composable
fun SourceTabRow(
    tabs: List<Pair<String, Int>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDoubleTap: ((Int) -> Unit)? = null,
) {
    if (tabs.size < 2) return
    // Tracking the last tap keeps the double-tap on the existing onClick instead of
    // wrapping every tab in its own gesture detector, which would swallow the select.
    var lastTap by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 8.dp,
        modifier = modifier,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        divider = {},
    ) {
        tabs.forEachIndexed { index, (label, count) ->
            Tab(
                selected = index == selectedIndex,
                onClick = {
                    val now = System.currentTimeMillis()
                    val previous = lastTap
                    if (onDoubleTap != null && previous?.first == index && now - (previous.second) < 400) {
                        lastTap = null
                        onDoubleTap(index)
                    } else {
                        lastTap = index to now
                        onSelect(index)
                    }
                },
                text = { Text("$label $count", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
