package com.anbhosting.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LocalWebsiteLogsScreen(
    store: LocalHostingStore,
    site: LocalSite,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(site.id) { mutableStateOf(emptyList<LocalHttpRequestLog>()) }
    var summary by remember(site.id) { mutableStateOf(LocalHttpRequestSummary(0, 0)) }
    var traffic30Days by remember(site.id) { mutableStateOf(0L) }
    var errorsOnly by remember(site.id) { mutableStateOf(false) }
    var search by remember(site.id) { mutableStateOf("") }
    var loading by remember(site.id) { mutableStateOf(false) }
    var hasMore by remember(site.id) { mutableStateOf(true) }
    var errorMessage by remember(site.id) { mutableStateOf("") }
    var infoMessage by remember(site.id) { mutableStateOf("") }
    var loadJob by remember(site.id) { mutableStateOf<Job?>(null) }

    fun load(reset: Boolean) {
        if (!reset && (loading || !hasMore)) return
        if (reset) {
            loadJob?.cancel()
            entries = emptyList()
            hasMore = true
        }
        val cursor = if (reset) null else entries.lastOrNull()?.id
        loadJob = scope.launch {
            loading = true
            errorMessage = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    val page = store.httpRequestLogs(
                        siteId = site.id,
                        errorsOnly = errorsOnly,
                        beforeId = cursor,
                    )
                    val stats = if (reset) store.httpRequestSummary(site.id) else null
                    val traffic = if (reset) store.trafficLast30Days(site.id) else null
                    Triple(page, stats, traffic)
                }
                entries = if (reset) result.first else entries + result.first
                hasMore = result.first.size == LocalHostingStore.REQUEST_LOG_PAGE_SIZE
                result.second?.let { summary = it }
                result.third?.let { traffic30Days = it }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                errorMessage = failure.message ?: "Website request logs could not be loaded."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(site.id, errorsOnly) { load(reset = true) }
    BackHandler(onBack = onBack)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                infoMessage = ""
                errorMessage = ""
                try {
                    val csv = withContext(Dispatchers.IO) { store.requestLogsCsv(site.id) }
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("The selected file could not be opened.")
                        output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    }
                    infoMessage = "CSV saved with up to ${LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE} recent requests."
                } catch (failure: Exception) {
                    errorMessage = failure.message ?: "The CSV export could not be saved."
                }
            }
        }
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = LocalBlue)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F6FB)) {
            Column(Modifier.fillMaxSize()) {
                LocalHeroHeader(
                    eyebrow = "TRAFFIC · ERRORS · HEALTH",
                    title = "Request activity",
                    subtitle = "${site.name} · request history is stored on this phone.",
                    backLabel = "Website",
                    onBack = onBack,
                    actions = {
                        TextButton(onClick = { load(reset = true) }, enabled = !loading) {
                            Text("Refresh", color = Color.White)
                        }
                        TextButton(onClick = { exportLauncher.launch("${site.slug}-request-logs.csv") }) {
                            Text("Export", color = Color.White)
                        }
                    },
                )

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LocalMetricCard("Requests / 24h", summary.requestsLast24Hours.toString(), Modifier.weight(1f))
                            LocalMetricCard("Errors / 24h", summary.errorsLast24Hours.toString(), Modifier.weight(1f))
                            LocalMetricCard("Traffic / 30d", formatLogBytes(traffic30Days), Modifier.weight(1f))
                        }
                    }
                    item {
                        Text(
                            "Real HTTP requests · latest ${LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE} per website are retained.",
                            color = LocalMuted,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (errorsOnly) {
                                OutlinedButton(onClick = { errorsOnly = false }, enabled = !loading, shape = RoundedCornerShape(14.dp)) { Text("All requests") }
                            } else {
                                Button(onClick = { errorsOnly = true }, enabled = !loading, shape = RoundedCornerShape(14.dp)) { Text("All requests") }
                            }
                            if (errorsOnly) {
                                Button(onClick = { errorsOnly = false }, enabled = !loading, shape = RoundedCornerShape(14.dp)) { Text("Errors only") }
                            } else {
                                OutlinedButton(onClick = { errorsOnly = true }, enabled = !loading, shape = RoundedCornerShape(14.dp)) { Text("Errors only") }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search path, status, or error") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                    if (errorMessage.isNotBlank()) item { LogNotice(errorMessage, isError = true) }
                    if (infoMessage.isNotBlank()) item { LogNotice(infoMessage, isError = false) }
                    if (loading && entries.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading request logs…", color = LocalMuted, fontSize = 12.sp)
                        }
                    }

                    val filtered = entries.filter { entry ->
                        val needle = search.trim()
                        needle.isBlank() || listOf(
                            entry.method,
                            entry.path,
                            entry.statusCode.toString(),
                            entry.errorMessage,
                        ).any { it.contains(needle, ignoreCase = true) }
                    }
                    if (!loading && entries.isEmpty() && errorMessage.isBlank()) item {
                        LocalPremiumCard {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (errorsOnly) "No website errors" else "No requests recorded yet", color = LocalNavy, fontWeight = FontWeight.Bold)
                                Text(
                                    if (errorsOnly) "Requests returning an error will appear here."
                                    else "Start this website, then open it in a browser to create request logs.",
                                    color = LocalMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty() && entries.isNotEmpty()) item {
                        Text("No loaded requests match this search.", color = LocalMuted, fontSize = 12.sp)
                    }
                    items(filtered, key = { it.id }) { entry ->
                        RequestLogCard(entry)
                    }
                    if (hasMore && entries.isNotEmpty()) item {
                        OutlinedButton(
                            onClick = { load(reset = false) },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(if (loading) "Loading…" else "Load more") }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RequestLogCard(entry: LocalHttpRequestLog) {
    val failed = entry.isError
    val status = when (entry.statusCode) {
        0 -> "Connection failed"
        else -> "HTTP ${entry.statusCode}"
    }
    LocalPremiumCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.method, color = LocalNavy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                LocalStatusPill(
                    text = status,
                    color = if (failed) Color(0xFFB42318) else LocalGreen,
                    background = if (failed) Color(0xFFFFEEEC) else Color(0xFFE9F7F0),
                )
            }
            Text(entry.path, color = LocalBlue, fontSize = 12.sp)
            Text(
                "${formatLogBytes(entry.responseBytes)} sent · ${DateFormat.getDateTimeInstance().format(Date(entry.createdAt))}",
                color = LocalMuted,
                fontSize = 10.sp,
            )
            if (entry.errorMessage.isNotBlank()) {
                Text(entry.errorMessage, color = Color(0xFFB42318), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LogNotice(message: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isError) Color(0xFFFFF1F0) else Color(0xFFEAF7F1)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isError) Color(0xFFF4C8C5) else Color(0xFFB9E4D0),
        ),
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(12.dp),
            color = if (isError) Color(0xFFB42318) else LocalGreen,
            fontSize = 12.sp,
        )
    }
}

private fun formatLogBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}
