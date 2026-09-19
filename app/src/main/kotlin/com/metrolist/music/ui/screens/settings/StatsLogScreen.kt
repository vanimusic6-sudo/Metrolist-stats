package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.StatsLog
import com.metrolist.music.utils.StatsLogController
import java.util.Date

/**
 * Counts worth reading at a glance, derived from the captured lines alone.
 *
 * Every field comes from what Metrolist or the wire interceptor already logs, so the summary can
 * never disagree with the lines below it.
 */
private data class StatsSummary(
    val opens: Int,
    val refusals: Int,
    val transportFailures: Int,
    val expiredUrlRecoveries: Int,
    val clientRollovers: Int,
    val abandonedSongs: Int,
) {
    val refusalPercent: Int get() = if (opens == 0) 0 else refusals * 100 / opens
}

private fun summarise(lines: List<String>): StatsSummary {
    var opens = 0
    var refusals = 0
    var transportFailures = 0
    var expiredUrl = 0
    var rollovers = 0
    var abandoned = 0
    for (line in lines) {
        when {
            "cdn-wire-iofail" in line -> transportFailures += 1
            "cdn-wire " in line -> {
                opens += 1
                val status = line.substringAfter("status=", "").takeWhile { it.isDigit() }
                if ((status.toIntOrNull() ?: 0) >= 400) refusals += 1
            }
            "Expired URL" in line -> expiredUrl += 1
            "trying the next client" in line -> rollovers += 1
            "exceeded retry limit" in line -> abandoned += 1
        }
    }
    return StatsSummary(opens, refusals, transportFailures, expiredUrl, rollovers, abandoned)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsLogScreen(navController: NavController) {
    val context = LocalContext.current
    val entries by StatsLog.logs.collectAsState()
    val capturing = StatsLog.isEnabled

    LaunchedEffect(Unit) { StatsLog.refresh() }

    val formatted = remember(entries) { entries.map(StatsLog::format) }
    val summary = remember(formatted) { summarise(formatted) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ),
    ) {
        Spacer(Modifier.height(64.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.stats_capture), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = capturing,
                onCheckedChange = { StatsLogController.setEnabled(it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                StatRow(stringResource(R.string.stats_opens), summary.opens.toString())
                StatRow(
                    stringResource(R.string.stats_refusals),
                    "${summary.refusals} (${summary.refusalPercent}%)",
                )
                StatRow(stringResource(R.string.stats_transport_failures), summary.transportFailures.toString())
                StatRow(stringResource(R.string.stats_expired_url), summary.expiredUrlRecoveries.toString())
                StatRow(stringResource(R.string.stats_client_rollovers), summary.clientRollovers.toString())
                StatRow(stringResource(R.string.stats_abandoned), summary.abandonedSongs.toString())
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val stamp = DateFormat.format("yyyy-MM-dd_HH-mm-ss", Date()).toString()
                    val body =
                        buildString {
                            appendLine("=== Metrolist Stats Logs ===")
                            appendLine("Exported: $stamp")
                            appendLine("Count: ${formatted.size}")
                            appendLine("==========================")
                            appendLine()
                            formatted.forEach { appendLine(it) }
                        }
                    val share =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "metrolist-stats-$stamp.txt")
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                    context.startActivity(Intent.createChooser(share, null))
                },
            ) { Text(stringResource(R.string.stats_share)) }

            FilledTonalButton(onClick = { StatsLog.clear() }) {
                Text(stringResource(R.string.stats_clear))
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(formatted) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.stats_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
