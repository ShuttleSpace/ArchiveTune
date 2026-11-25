/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.viewmodels.DeviceSyncViewModel

@Composable
fun DeviceSyncScreen(
    navController: NavController,
    viewModel: DeviceSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var host by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.device_sync_title)) },
                subtitle = { Text(stringResource(R.string.device_sync_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.back_button_desc))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle(stringResource(R.string.sync_receive_title)) }
            item { Hint(stringResource(R.string.sync_receive_hint)) }
            if (state.addresses.isEmpty()) {
                item { Hint(stringResource(R.string.sync_no_network)) }
            } else {
                items(state.addresses.size) { index ->
                    Text(
                        text = "${state.addresses[index]}:8642",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (state.hosting) {
                item {
                    Text(
                        text = "${stringResource(R.string.sync_code_label)} ${state.code}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Button(onClick = { if (state.hosting) viewModel.stopHosting() else viewModel.startHosting() }) {
                    Text(stringResource(if (state.hosting) R.string.sync_stop else R.string.sync_start))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.sync_send_title))
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.sync_ip_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.sync_code_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { viewModel.send(host, code) }, enabled = !state.busy) {
                            Text(stringResource(if (state.busy) R.string.sync_sending else R.string.sync_send_button))
                        }
                    }
                }
            }
            state.status?.let { text -> item { Hint(text) } }
            state.error?.let { text ->
                item { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
