/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.modules.ModuleSongStore
import moe.rukamori.archivetune.ui.component.SourcePickerRow
import moe.rukamori.archivetune.ui.component.SourcePickerSheet
import moe.rukamori.archivetune.ui.component.SourceTabRow
import moe.rukamori.archivetune.ui.component.sourceChipLabel
import moe.rukamori.archivetune.ui.component.SourceErrorDialog
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playlistimport.ImportReplacementSearcher
import moe.rukamori.archivetune.playlistimport.buildImportSongQuery
import moe.rukamori.archivetune.viewmodels.SourceWorkbenchViewModel

@Composable
fun SongSourceReplacementDialog(
    currentSong: Song,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Song) -> Unit,
) {
    val replacementSearcher = remember { ImportReplacementSearcher() }
    // Custom sources: reuse the workbench so search semantics stay identical everywhere.
    val workbenchViewModel: SourceWorkbenchViewModel = hiltViewModel()
    val workbenchState by workbenchViewModel.state.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val localLibrary by
        database
            .importSongCandidates()
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val noResultsMessage = stringResource(R.string.import_no_search_results)

    var query by remember(currentSong.id) { mutableStateOf(buildImportSongQuery(currentSong)) }
    var candidates by remember(currentSong.id) { mutableStateOf(emptyList<Song>()) }
    var candidateSource by remember(currentSong.id) { mutableStateOf<ReplacementSource?>(null) }
    var searchingSource by remember(currentSong.id) { mutableStateOf<ReplacementSource?>(null) }
    var selectedSong by remember(currentSong.id) { mutableStateOf<Song?>(null) }
    var previewSongId by remember(currentSong.id) { mutableStateOf<String?>(null) }
    var searchMessage by remember(currentSong.id) { mutableStateOf<String?>(null) }

    fun searchLocal() {
        val requestedQuery = query
        searchingSource = ReplacementSource.LOCAL
        candidateSource = ReplacementSource.LOCAL
        candidates = emptyList()
        selectedSong = null
        searchMessage = null
        coroutineScope.launch {
            val matches =
                withContext(Dispatchers.Default) {
                    replacementSearcher.searchLocal(
                        query = requestedQuery,
                        localLibrary = localLibrary.filter { candidate -> candidate.song.isLocal },
                    )
                }
            if (query == requestedQuery) {
                candidates = matches
                searchMessage = noResultsMessage.takeIf { matches.isEmpty() }
                searchingSource = null
            }
        }
    }

    fun searchYouTube() {
        val requestedQuery = query
        searchingSource = ReplacementSource.YOUTUBE
        candidateSource = ReplacementSource.YOUTUBE
        candidates = emptyList()
        selectedSong = null
        searchMessage = null
        coroutineScope.launch {
            val matches = replacementSearcher.searchYouTube(requestedQuery)
            if (query == requestedQuery) {
                candidates = matches
                searchMessage = noResultsMessage.takeIf { matches.isEmpty() }
                searchingSource = null
            }
        }
    }

    var pickerOpen by remember { mutableStateOf(false) }

    fun searchSources() {
        candidateSource = ReplacementSource.SOURCE
        candidates = emptyList()
        selectedSong = null
        searchMessage = null
        workbenchViewModel.onQueryChange(query)
        workbenchViewModel.search()
    }

    BackHandler(enabled = !isSaving, onBack = onDismiss)
    Dialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.change_song_source)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                enabled = !isSaving,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = stringResource(android.R.string.cancel),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                enabled = !isSaving,
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                            Button(
                                onClick = { selectedSong?.let(onConfirm) },
                                enabled =
                                    selectedSong != null &&
                                        selectedSong?.id != currentSong.id &&
                                        !isSaving,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.replace_song))
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                ) {
                    // The tab row must sit on the same x as the search page's tab row, so the
                    // 16 dp content inset is applied to the text/controls instead of the whole
                    // column.
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = currentSong.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentSong.artistText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SourcePickerRow(
                        selectedCount = workbenchState.selectedModuleIds.size,
                        totalCount = workbenchState.modules.size,
                        query = query,
                        onQueryChange = {
                            query = it
                            candidates = emptyList()
                            selectedSong = null
                            searchMessage = null
                        },
                        searching = workbenchState.searching,
                        onOpenPicker = { pickerOpen = true },
                        onSearch = ::searchSources,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (pickerOpen) {
                        SourcePickerSheet(
                            modules = workbenchState.modules,
                            selectedIds = workbenchState.selectedModuleIds,
                            onToggle = workbenchViewModel::toggleModule,
                            onSelectAll = workbenchViewModel::selectAllModules,
                            onInvert = workbenchViewModel::invertModuleSelection,
                            onClear = workbenchViewModel::clearModuleSelection,
                            onDismiss = { pickerOpen = false },
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = ::searchLocal,
                            enabled = query.isNotBlank() && searchingSource == null && !isSaving,
                            modifier = Modifier.weight(1f),
                        ) {
                            SearchButtonContent(
                                loading = searchingSource == ReplacementSource.LOCAL,
                                text = stringResource(R.string.import_search_local),
                            )
                        }
                        Button(
                            onClick = ::searchYouTube,
                            enabled = query.isNotBlank() && searchingSource == null && !isSaving,
                            modifier = Modifier.weight(1f),
                        ) {
                            SearchButtonContent(
                                loading = searchingSource == ReplacementSource.YOUTUBE,
                                text = stringResource(R.string.import_search_youtube),
                            )
                        }
                    }
                    searchMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    }
                    val sourcePairs =
                        if (candidateSource == ReplacementSource.SOURCE) {
                            workbenchState.hits.mapNotNull { hit ->
                                workbenchState.modules
                                    .firstOrNull { module -> module.id == hit.moduleId }
                                    ?.let { module -> hit to ModuleSongStore.songOf(module, hit.track) }
                            }
                        } else {
                            emptyList()
                        }
                    val sourceHitOf = sourcePairs.associate { it.second.id to it.first }
                    val groupedHits =
                        if (candidateSource == ReplacementSource.SOURCE) {
                            sourcePairs.groupBy { (hit, _) -> hit.moduleId }
                        } else {
                            emptyMap()
                        }
                    val searchedIds =
                        workbenchState.searchedModuleIds
                            .ifEmpty { workbenchState.selectedModuleIds.ifEmpty { workbenchState.modules.map { it.id }.toSet() } }
                    val sourceOrder = workbenchState.modules.map { it.id }.filter { it in searchedIds }
                    // Tabs per source, same reason as the workbench: a paging button must visibly
                    // belong to one source instead of trailing whichever list came last.
                    var sourceTab by remember(sourceOrder) { mutableIntStateOf(0) }
                    val activeSourceIndex = sourceTab.coerceIn(0, (sourceOrder.size - 1).coerceAtLeast(0))
                    if (candidateSource == ReplacementSource.SOURCE) {
                        val notice =
                            when {
                                workbenchState.searching -> stringResource(R.string.source_workbench_searching)
                                workbenchState.modules.isEmpty() -> stringResource(R.string.source_workbench_empty_hint)
                                sourcePairs.isEmpty() && workbenchState.stats.isNotEmpty() -> noResultsMessage
                                else -> null
                            }
                        notice?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                    SourceTabRow(
                        tabs =
                            sourceOrder.map { moduleId ->
                                val module = workbenchState.modules.firstOrNull { it.id == moduleId }
                                (module?.manifest?.name ?: moduleId) to (groupedHits[moduleId]?.size ?: 0)
                            },
                        selectedIndex = activeSourceIndex,
                        onSelect = { sourceTab = it },
                        onDoubleTap = { index ->
                            sourceOrder.getOrNull(index)?.let { moduleId -> workbenchViewModel.refresh(moduleId) }
                        },
                    )
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                top = 4.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        if (candidateSource == ReplacementSource.SOURCE) {
                            val moduleId = sourceOrder.getOrNull(activeSourceIndex)
                            items(groupedHits[moduleId].orEmpty(), key = { (_, song) -> song.id }) { entry ->
                                val (hit, candidate) = entry
                                SourceCandidateRow(
                                    candidate = candidate,
                                    selected = selectedSong?.id == candidate.id,
                                    enabled = !isSaving,
                                    onSelect = { if (!isSaving) selectedSong = candidate },
                                    onPreview = {
                                        if (!isSaving) {
                                            previewSongId = candidate.id
                                            workbenchViewModel.play(hit)
                                        }
                                    },
                                )
                            }
                            if (moduleId != null && moduleId in workbenchState.moreSources) {
                                item(key = "more:$moduleId") {
                                    TextButton(
                                        onClick = { workbenchViewModel.loadMore(moduleId) },
                                        enabled = moduleId !in workbenchState.loadingMore && !isSaving,
                                    ) {
                                        Text(
                                            if (moduleId in workbenchState.loadingMore) {
                                                stringResource(R.string.source_workbench_searching)
                                            } else {
                                                stringResource(R.string.source_workbench_load_more_plain)
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            items(items = candidates, key = { candidate -> candidate.id }) { candidate ->
                                SourceCandidateRow(
                                    candidate = candidate,
                                    selected = selectedSong?.id == candidate.id,
                                    enabled = !isSaving,
                                    onSelect = { if (!isSaving) selectedSong = candidate },
                                    onPreview = {
                                        if (!isSaving) {
                                            previewSongId = candidate.id
                                            playerConnection?.playQueue(
                                                ListQueue(
                                                    title = candidate.song.title,
                                                    items = listOf(candidate.toMediaItem()),
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    workbenchState.error?.let { error ->
        SourceErrorDialog(
            title = stringResource(R.string.source_workbench_error_title),
            message = error,
            onDismiss = workbenchViewModel::dismissError,
        )
    }
}

/** Row = select + preview, shared by local/YouTube results and custom-source hits. */
@Composable
private fun SourceCandidateRow(
    candidate: Song,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    // Same shape as the workbench row on purpose: two result lists that look different
    // force the reader to re-learn them.
    ListItem(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onSelect),
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
            ),
        headlineContent = {
            Text(candidate.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                listOfNotNull(
                    candidate.artists.joinToString("/") { it.name }.ifBlank { null },
                    candidate.song.duration.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                    candidate.song.albumName,
                ).joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Preview before committing: a resolved URL does not prove the track plays.
                TextButton(onClick = onPreview, enabled = enabled) {
                    Text(stringResource(R.string.source_workbench_preview))
                }
                TextButton(onClick = onSelect, enabled = enabled) {
                    Text(
                        stringResource(
                            if (selected) R.string.source_workbench_selected else R.string.source_workbench_select,
                        ),
                    )
                }
            }
        },
    )
}

private enum class ReplacementSource {
    LOCAL,
    YOUTUBE,
    SOURCE,
}

private val Song.artistText: String
    get() = artists.joinToString(" · ") { it.name }.ifBlank { "—" }
