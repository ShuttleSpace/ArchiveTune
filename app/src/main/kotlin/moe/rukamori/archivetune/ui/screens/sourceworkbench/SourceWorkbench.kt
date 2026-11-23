/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.sourceworkbench
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.constants.ShowSourceDebugDockKey
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.SmallFloatingActionButton

import androidx.compose.ui.text.AnnotatedString

import androidx.compose.ui.platform.LocalClipboardManager

import android.widget.Toast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.CarMode
import moe.rukamori.archivetune.ui.component.SourcePickerRow
import moe.rukamori.archivetune.ui.component.SourcePickerSheet
import moe.rukamori.archivetune.ui.component.SourceTabRow
import moe.rukamori.archivetune.ui.component.sourceChipLabel
import moe.rukamori.archivetune.ui.component.SourceErrorDialog
import moe.rukamori.archivetune.viewmodels.SourceWorkbenchViewModel

/**
 * Source workbench: search an installed module directly and resolve a stream.
 * ArchiveTune only uses modules to replace audio for an existing song, so this
 * screen is the entry point that makes the bundled sources browsable on their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val MINI_PLAYER_INSET = 96.dp

@Composable
fun SourceWorkbenchScreen(
    navController: NavController,
    showBack: Boolean = true,
    /** When set, rows add the track to this playlist instead of previewing it. */
    playlistId: String? = null,
    /** Pre-filled query, e.g. when the shell search bar forwards what the user typed. */
    initialQuery: String? = null,
    viewModel: SourceWorkbenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            viewModel.onQueryChange(initialQuery)
            viewModel.search()
        }
    }
    val listState = rememberLazyListState()
    val playerConnection = LocalPlayerConnection.current

    // Hand the resolved stream to the app player instead of a screen-local one, so it
    // keeps playing after leaving this screen and shows in the mini player/notification.
    LaunchedEffect(state.stream) {
        val stream = state.stream ?: return@LaunchedEffect
        // The mini player reads `MediaItem.localConfiguration.tag` (see
        // extensions/MediaItemExt.kt:32), not media3's MediaMetadata, so the tag is
        // what actually fills its title/artist/album.
        val track = viewModel.state.value.playingTrack
        val tag =
            moe.rukamori.archivetune.models.MediaMetadata(
                id = stream.upstreamId ?: stream.url,
                title = track?.title ?: viewModel.state.value.playingTitle.orEmpty(),
                artists = track?.artists.orEmpty().map { moe.rukamori.archivetune.models.MediaMetadata.Artist(null, it) },
                duration = track?.durationSeconds ?: 0,
                thumbnailUrl = track?.thumbnailUrl,
                album = track?.album?.let { moe.rukamori.archivetune.models.MediaMetadata.Album("", it) },
            )
        playerConnection?.playNow(
            MediaItem
                .Builder()
                .setMediaId(stream.upstreamId ?: stream.url)
                .setUri(stream.url)
                .setMimeType(stream.mimeType)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(tag.title)
                        .setArtist(tag.artists.joinToString { it.name })
                        .setIsPlayable(true)
                        .build(),
                )
                .setTag(tag)
                .build(),
        )
        viewModel.onStreamConsumed()
    }

    // The shell draws a floating header over tab content; when we hide our own top bar
    // the content must clear it, so use the same inset the previous search screen used.
    val playerInsets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    var pickerOpen by remember { mutableStateOf(false) }

    // No top bar in either presentation: the shell already shows a title when we are a tab,
    // and a pushed instance puts its back affordance at the start of the source chips. That way
    // both presentations have identical content geometry.
    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(
                        top =
                            if (showBack) {
                                0.dp
                            } else {
                                (playerInsets.calculateTopPadding() - padding.calculateTopPadding())
                                    .coerceAtLeast(0.dp)
                            },
                    )
                    // Keep the last rows above the mini player: it is an overlay,
                    // so anything drawn at the bottom is hidden behind it.
                    .padding(bottom = MINI_PLAYER_INSET),
        ) {
            if (state.modules.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.source_workbench_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.source_workbench_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { navController.navigate("settings/modules") }) { Text(stringResource(R.string.source_workbench_open_modules)) }
                }
                return@Column
            }

            SourcePickerRow(
                selectedCount = state.selectedModuleIds.size,
                totalCount = state.modules.size,
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                searching = state.searching,
                onOpenPicker = { pickerOpen = true },
                onSearch = viewModel::search,
                leading =
                    if (showBack) {
                        {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_back),
                                    contentDescription = stringResource(R.string.source_workbench_back),
                                )
                            }
                        }
                    } else {
                        null
                    },
            )

            state.playingTitle?.let { title ->
                Text(
                    text = stringResource(R.string.source_workbench_now_playing, title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (pickerOpen) {
                SourcePickerSheet(
                    modules = state.modules,
                    selectedIds = state.selectedModuleIds,
                    onToggle = viewModel::toggleModule,
                    onSelectAll = viewModel::selectAllModules,
                    onInvert = viewModel::invertModuleSelection,
                    onClear = viewModel::clearModuleSelection,
                    onDismiss = { pickerOpen = false },
                )
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val sourceNames = state.modules.associate { module -> module.id to module.manifest.name }
            // Tabs instead of one concatenated list: a source's paging button then clearly belongs
            // to that source, and switching sources does not mean scrolling past the others.
            val grouped = state.hits.groupBy { it.moduleId }
            // Tabs follow the sources that were searched, not the ones that happened to return
            // something: a failing source must stay visible as its own empty tab.
            val tabSources =
                state.modules.filter { module ->
                    module.id in
                        (state.searchedModuleIds.ifEmpty { state.selectedModuleIds.ifEmpty { state.modules.map { it.id }.toSet() } })
                }
            var tabIndex by remember(tabSources) { mutableIntStateOf(0) }
            val activeIndex = tabIndex.coerceIn(0, (tabSources.size - 1).coerceAtLeast(0))
            // Only after a search: empty tabs before the first query are noise. Labels stay short
            // (bare name) because a tab is a space-limited surface.
            if (state.searchedModuleIds.isNotEmpty() || state.hits.isNotEmpty()) {
                SourceTabRow(
                    tabs = tabSources.map { module -> module.manifest.name to (grouped[module.id]?.size ?: 0) },
                    selectedIndex = activeIndex,
                    onSelect = { tabIndex = it },
                )
            }
            val activeSource = tabSources.getOrNull(activeIndex)?.id
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Without this the last row hides behind the mini player.
                contentPadding = PaddingValues(bottom = MINI_PLAYER_INSET + 16.dp),
            ) {
                items(grouped[activeSource].orEmpty(), key = { "${it.moduleId}:${it.track.id}" }) { hit ->
                    val carMode = CarMode.enabled
                    ListItem(
                        modifier = if (carMode) Modifier.heightIn(min = 64.dp) else Modifier,
                        headlineContent = {
                            Text(hit.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val artists = hit.track.artists.joinToString("/")
                            val duration = hit.track.durationSeconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
                            Text(
                                listOfNotNull(
                                    artists.ifBlank { null },
                                    duration,
                                    hit.track.album,
                                ).joinToString(" · "),
                                // A car screen is 600dp tall; one line per row keeps six rows visible.
                                maxLines = if (carMode) 1 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            val touch = if (carMode) Modifier.heightIn(min = 48.dp) else Modifier
                            if (playlistId == null) {
                                TextButton(modifier = touch, onClick = { viewModel.play(hit) }) { Text(stringResource(R.string.source_workbench_preview)) }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Preview first: nothing else tells you whether a source can
                                    // actually play this track before it lands in the playlist.
                                    TextButton(modifier = touch, onClick = { viewModel.play(hit) }) {
                                        Text(stringResource(R.string.source_workbench_preview))
                                    }
                                    TextButton(
                                        modifier = touch,
                                        onClick = {
                                            scope.launch { viewModel.addToPlaylist(playlistId, hit) }
                                        },
                                    ) {
                                        Text(stringResource(R.string.source_workbench_add_to_playlist))
                                    }
                                }
                            }
                        },
                    )
                }
                if (activeSource != null && activeSource in state.moreSources) {
                    item(key = "more:$activeSource") {
                        TextButton(
                            onClick = { viewModel.loadMore(activeSource) },
                            enabled = activeSource !in state.loadingMore,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text =
                                    if (activeSource in state.loadingMore) {
                                        stringResource(R.string.source_workbench_searching)
                                    } else {
                                        stringResource(R.string.source_workbench_load_more_plain)
                                    },
                            )
                        }
                    }
                }
            }
        }
            state.error?.let { error ->
                SourceErrorDialog(
                    title = stringResource(R.string.source_workbench_error_title),
                    message = error,
                    onDismiss = viewModel::dismissError,
                )
            }
            val debugContext = LocalContext.current
            var dockEnabled by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                dockEnabled = debugContext.dataStore.get(ShowSourceDebugDockKey, true)
            }
            // The dock is a developer surface; on a 600dp-tall car screen it would sit on
            // top of the results, so the car build leaves it off.
            if (dockEnabled && !CarMode.enabled) {
                SourceDebugDock(
                    state = state,
                    onClear = viewModel::clearTrace,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = MINI_PLAYER_INSET + 96.dp),
                )
            }
        }
    }
}

/**
 * Fork feature: debug console as a collapsible floating dock. It floats above the mini
 * player so it costs no layout space until opened.
 */
@Composable
private fun SourceDebugDock(
    state: SourceWorkbenchViewModel.UiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Draggable so it can be moved off whatever it covers.
    val dragModifier =
        modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offsetX += drag.x
                    offsetY += drag.y
                }
            }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    if (!expanded) {
        SmallFloatingActionButton(
            onClick = { expanded = true },
            modifier = dragModifier,
        ) {
            Text(
                text = if (state.stats.values.sumOf { it.failed + it.empty } > 0) "!" else stringResource(R.string.source_workbench_debug),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        return
    }

    Surface(
        modifier = dragModifier.widthIn(max = 340.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val debugCopiedLabel = stringResource(R.string.source_workbench_debug_copied)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.source_workbench_debug), style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(
                        onClick = {
                            val report =
                                buildString {
                                    state.stats.forEach { (name, stat) ->
                                        appendLine(
                                            "$name · ${stat.queries} runs · ${stat.empty} empty · " +
                                                "${stat.failed} failed · last ${stat.lastMs}ms" +
                                                (stat.lastError?.let { " · $it" } ?: ""),
                                        )
                                    }
                                    state.trace.forEach { appendLine(it) }
                                }
                            clipboard.setText(AnnotatedString(report))
                            Toast.makeText(context, debugCopiedLabel, Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(stringResource(R.string.source_workbench_debug_copy), style = MaterialTheme.typography.labelMedium) }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.source_workbench_debug_clear), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { expanded = false }) {
                        Text(stringResource(R.string.source_workbench_debug_collapse), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            state.stats.forEach { (name, stat) ->
                Text(
                    text = stringResource(R.string.source_workbench_debug_stat, name, stat.queries, stat.empty, stat.failed, stat.lastMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            HorizontalDivider()
            state.trace.take(4).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.trace.isEmpty()) {
                Text(stringResource(R.string.source_workbench_debug_empty), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
