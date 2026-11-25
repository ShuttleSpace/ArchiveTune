package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.recommend.ModuleRecommendation
import moe.rukamori.archivetune.viewmodels.RecommendationsViewModel

/**
 * Home body for the no-YouTube-account case: instead of a dead error pane, suggest tracks the
 * installed custom sources return for the artists in the custom-source play history.
 */
@Composable
fun DailyRecommendationsSection(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: RecommendationsViewModel = hiltViewModel(),
) {
    val entries by viewModel.daily.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val connection = LocalPlayerConnection.current

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.recommend_daily_title),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.recommend_refresh),
                )
            }
        }

        when {
            loading && entries.isEmpty() ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            entries.isEmpty() ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.recommend_daily_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { navController.navigate("settings/modules/workbench") }) {
                        Text(stringResource(R.string.recommend_open_sources))
                    }
                }

            else ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { "${it.module.id}:${it.track.id}" }) { item ->
                        RecommendationRow(
                            item = item,
                            playing = playingId == item.track.id,
                            onPlay = { connection?.let { viewModel.play(item, it) } },
                        )
                    }
                }
        }
    }
}

@Composable
fun RecommendationRow(
    item: ModuleRecommendation,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    val track = item.track
    val subtitle =
        listOfNotNull(
            track.artists.joinToString(", ").ifBlank { null },
            item.module.manifest.name,
        ).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = onPlay, enabled = !playing) {
                Text(
                    stringResource(
                        if (playing) R.string.recommend_loading else R.string.recommend_play,
                    ),
                )
            }
        },
    )
}
