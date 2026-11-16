package com.anbhosting.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal val LocalNavy = Color(0xFF101C3A)
internal val LocalBlue = Color(0xFF3157D5)
internal val LocalMuted = Color(0xFF718096)
internal val LocalGreen = Color(0xFF16865B)

private data class LocalHostingSnapshot(
    val sites: List<LocalSite>,
    val events: List<LocalHostingEvent>,
    val usageBySite: Map<String, LocalSiteUsage>,
    val deploymentsBySite: Map<String, LocalDeployment>,
    val deviceMetrics: LocalDeviceMetrics,
)

@Composable
internal fun LocalHostingScreen(
    store: LocalHostingStore,
    onBack: () -> Unit,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit,
    onReconfigureHosting: () -> Unit,
    onRequestHostingPermission: (onGranted: () -> Unit, onDenied: () -> Unit) -> Unit,
    onOpenPreview: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileManager = remember(store) { LocalFileManager(store) }
    val githubDeployer = remember(context.applicationContext, store, fileManager) {
        LocalGithubDeployer(context.applicationContext, store, fileManager)
    }
    val resourceMonitor = remember(context) { LocalResourceMonitor(context) }
    var sites by remember { mutableStateOf(emptyList<LocalSite>()) }
    var usageBySite by remember { mutableStateOf(emptyMap<String, LocalSiteUsage>()) }
    var deviceMetrics by remember { mutableStateOf(LocalDeviceMetrics(0L, 0L, null)) }
    var events by remember { mutableStateOf(emptyList<LocalHostingEvent>()) }
    var deploymentsBySite by remember { mutableStateOf(emptyMap<String, LocalDeployment>()) }
    var allowLan by remember { mutableStateOf(store.isLanSharingEnabled()) }
    var keepCpuAwake by remember { mutableStateOf(store.isKeepCpuAwakeEnabled()) }
    var siteName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var showGithubDeployment by remember { mutableStateOf(false) }
    var githubRepositoryUrl by remember { mutableStateOf("") }
    var githubSiteName by remember { mutableStateOf("") }
    var githubPublishDirectory by remember { mutableStateOf("") }
    var githubRef by remember { mutableStateOf("") }
    var targetSiteId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalSite?>(null) }
    var pendingRollback by remember { mutableStateOf<LocalDeployment?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var phoneAddress by remember { mutableStateOf(LocalNetwork.wifiIpv4Address(context)?.hostAddress) }
    var managedSite by remember { mutableStateOf<LocalSite?>(null) }

    BackHandler(enabled = managedSite == null, onBack = onBack)

    fun reload() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val allSites = store.listSites()
                    LocalHostingSnapshot(
                        sites = allSites,
                        events = store.recentEvents(),
                        usageBySite = allSites.associate { it.id to fileManager.measureUsage(it) },
                        deploymentsBySite = allSites.mapNotNull { site ->
                            store.latestAppliedDeployment(site.id)?.let { site.id to it }
                        }.toMap(),
                        deviceMetrics = resourceMonitor.sample(),
                    )
                }
                sites = result.sites
                events = result.events
                usageBySite = result.usageBySite
                deploymentsBySite = result.deploymentsBySite
                deviceMetrics = result.deviceMetrics
                allowLan = store.isLanSharingEnabled()
                keepCpuAwake = store.isKeepCpuAwakeEnabled()
                phoneAddress = LocalNetwork.wifiIpv4Address(context)?.hostAddress
                error = ""
            } catch (failure: Exception) {
                error = failure.message ?: "Local hosting information could not be loaded."
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun startSite(site: LocalSite) {
        onRequestHostingPermission(
            onGranted = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        withContext(Dispatchers.IO) {
                            store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
                        }
                        onStartHosting()
                        reload()
                    } catch (failure: Exception) {
                        error = failure.message ?: "Website could not be started."
                        withContext(Dispatchers.IO) {
                            runCatching { store.setSiteStatus(site.id, LocalHostingStore.STATUS_STOPPED) }
                        }
                    } finally {
                        busy = false
                    }
                }
            },
            onDenied = { error = "Notification permission is needed to keep local hosting visible while it runs." },
        )
    }

    fun stopSite(site: LocalSite) {
        scope.launch {
            busy = true
            error = ""
            try {
                val remaining = withContext(Dispatchers.IO) {
                    store.setSiteStatus(site.id, LocalHostingStore.STATUS_STOPPED)
                    store.listSites().any { it.status == LocalHostingStore.STATUS_ACTIVE }
                }
                if (!remaining) onStopHosting()
                reload()
            } catch (failure: Exception) {
                error = failure.message ?: "Website could not be stopped."
            } finally {
                busy = false
            }
        }
    }

    fun deleteSite(site: LocalSite) {
        scope.launch {
            busy = true
            error = ""
            try {
                val remaining = withContext(Dispatchers.IO) {
                    if (site.status == LocalHostingStore.STATUS_ACTIVE) {
                        store.setSiteStatus(site.id, LocalHostingStore.STATUS_STOPPED)
                    }
                    store.deleteSite(site.id)
                    store.listSites().any { it.status == LocalHostingStore.STATUS_ACTIVE }
                }
                if (!remaining) onStopHosting()
                pendingDelete = null
                reload()
            } catch (failure: Exception) {
                error = failure.message ?: "Website could not be deleted."
            } finally {
                busy = false
            }
        }
    }

    fun deployGithubSite() {
        scope.launch {
            busy = true
            error = ""
            try {
                val deployedSite = withContext(Dispatchers.IO) {
                    githubDeployer.deploy(
                        githubRepositoryUrl,
                        githubSiteName,
                        githubPublishDirectory,
                        githubRef,
                        targetSiteId,
                    )
                }
                showGithubDeployment = false
                githubRepositoryUrl = ""
                githubSiteName = ""
                githubPublishDirectory = ""
                githubRef = ""
                targetSiteId = null
                reload()
                startSite(deployedSite)
            } catch (failure: Exception) {
                error = failure.message ?: "GitHub deployment failed."
            } finally {
                busy = false
            }
        }
    }

    fun rollbackDeployment(deployment: LocalDeployment) {
        scope.launch {
            busy = true
            error = ""
            try {
                withContext(Dispatchers.IO) { githubDeployer.rollback(deployment.id, deployment.siteId) }
                pendingRollback = null
                reload()
            } catch (failure: Exception) {
                error = failure.message ?: "Deployment rollback failed."
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = LocalBlue)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F6FB)) {
            val selectedSite = managedSite
            if (selectedSite != null) {
                LocalFileManagerScreen(
                    manager = fileManager,
                    site = selectedSite,
                    onBack = { managedSite = null; reload() },
                )
            } else Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(LocalNavy).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("‹ Back", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { reload() }) { Text("Refresh", color = Color.White) }
                        Text("LOCAL MODE", color = Color(0xFFB9C7E7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Host on this phone", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Sites and settings are stored on this device.", color = Color(0xFFB9C7E7), fontSize = 13.sp)
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Share on Wi-Fi", color = LocalNavy, fontWeight = FontWeight.Bold)
                                        Text("Off by default · read-only website access", color = LocalMuted, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = allowLan,
                                        onCheckedChange = { enabled ->
                                            allowLan = enabled
                                            error = ""
                                            scope.launch {
                                                withContext(Dispatchers.IO) { store.setLanSharingEnabled(enabled) }
                                                if (sites.any { it.status == LocalHostingStore.STATUS_ACTIVE }) onReconfigureHosting()
                                                reload()
                                            }
                                        },
                                    )
                                }
                                if (allowLan) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        phoneAddress?.let { "Wi-Fi address: http://$it:${LocalStaticServer.PORT}/s/<site>/" }
                                            ?: "Wi-Fi address is unavailable. Connect this phone to Wi-Fi first.",
                                        color = LocalBlue,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        "Devices on the same Wi-Fi can view active sites. Your router must allow devices to communicate.",
                                        color = LocalMuted,
                                        fontSize = 11.sp,
                                    )
                                } else {
                                    Text("Preview uses this phone's localhost address.", color = LocalMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Keep hosting responsive with screen off", color = LocalNavy, fontWeight = FontWeight.Bold)
                                        Text("Uses more battery while local sites are running.", color = LocalMuted, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = keepCpuAwake,
                                        onCheckedChange = { enabled ->
                                            keepCpuAwake = enabled
                                            error = ""
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) { store.setKeepCpuAwakeEnabled(enabled) }
                                                    if (sites.any { it.status == LocalHostingStore.STATUS_ACTIVE }) onReconfigureHosting()
                                                    reload()
                                                } catch (failure: Exception) {
                                                    keepCpuAwake = !enabled
                                                    error = failure.message ?: "The screen-off hosting setting could not be saved."
                                                }
                                            }
                                        },
                                    )
                                }
                                Text(
                                    "Android battery saver and Doze may still delay network access. Adjust this app's battery use in system settings for the best chance of continuous hosting.",
                                    color = LocalMuted,
                                    fontSize = 11.sp,
                                )
                                TextButton(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        runCatching { context.startActivity(intent) }
                                            .onFailure { error = "App battery settings could not be opened." }
                                    },
                                ) { Text("Open app battery settings") }
                            }
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UsageMetric("ANB RAM", formatBytes(deviceMetrics.appMemoryBytes), Modifier.weight(1f))
                            UsageMetric(
                                "App CPU",
                                deviceMetrics.appCpuPercent?.let { "${"%.1f".format(it)}%" } ?: "Sample again",
                                Modifier.weight(1f),
                            )
                            UsageMetric("Free storage", formatBytes(deviceMetrics.freeStorageBytes), Modifier.weight(1f))
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Local websites", color = LocalNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("${sites.size} saved · ${sites.count { it.status == LocalHostingStore.STATUS_ACTIVE }} running", color = LocalMuted, fontSize = 12.sp)
                            }
                            Button(onClick = { siteName = ""; showCreate = true }) { Text("New site") }
                        }
                        OutlinedButton(
                            onClick = {
                                targetSiteId = null
                                githubRepositoryUrl = ""
                                githubSiteName = ""
                                githubPublishDirectory = ""
                                githubRef = ""
                                error = ""
                                showGithubDeployment = true
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Deploy public GitHub site") }
                    }

                    if (error.isNotBlank()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F0))) {
                            Text(error, Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFFB42318), fontSize = 13.sp)
                        }
                    }

                    if (sites.isEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No local sites yet", color = LocalNavy, fontWeight = FontWeight.Bold)
                                Text("Create a site, then start it to preview the starter page offline.", color = LocalMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    items(sites, key = { it.id }) { site ->
                        val deployment = deploymentsBySite[site.id]
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, color = LocalNavy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(site.slug, color = LocalMuted, fontSize = 12.sp)
                                        val usage = usageBySite[site.id]
                                        Text(
                                            usage?.let {
                                                "${it.fileCount} files · ${formatBytes(it.storedBytes)} · " +
                                                    "${formatBytes(it.trafficLast30DaysBytes)} served/30d"
                                            } ?: "Measuring local usage…",
                                            color = LocalMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.size(7.dp).background(
                                            if (site.status == LocalHostingStore.STATUS_ACTIVE) LocalGreen else LocalMuted,
                                            CircleShape,
                                        ))
                                        Text(
                                            if (site.status == LocalHostingStore.STATUS_ACTIVE) " Running" else " Stopped",
                                            color = if (site.status == LocalHostingStore.STATUS_ACTIVE) LocalGreen else LocalMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (site.status == LocalHostingStore.STATUS_ACTIVE) {
                                        OutlinedButton(
                                            onClick = {
                                                val host = if (allowLan && phoneAddress != null) phoneAddress else "127.0.0.1"
                                                onOpenPreview("http://$host:${LocalStaticServer.PORT}/s/${site.slug}/")
                                            },
                                            enabled = !busy,
                                        ) { Text("Preview") }
                                        OutlinedButton(onClick = { stopSite(site) }, enabled = !busy) { Text("Stop") }
                                    } else {
                                        Button(onClick = { startSite(site) }, enabled = !busy) { Text("Start hosting") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { managedSite = site }, enabled = !busy) { Text("Files") }
                                    TextButton(onClick = { pendingDelete = site }, enabled = !busy) {
                                        Text("Delete website", color = Color(0xFFB42318))
                                    }
                                }
                                if (site.status == LocalHostingStore.STATUS_STOPPED) {
                                    OutlinedButton(
                                        onClick = {
                                            targetSiteId = site.id
                                            githubRepositoryUrl = ""
                                            githubSiteName = site.name
                                            githubPublishDirectory = ""
                                            githubRef = ""
                                            error = ""
                                            showGithubDeployment = true
                                        },
                                        enabled = !busy,
                                    ) { Text("Deploy update from GitHub") }
                                }
                                deployment?.let { latest ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "GitHub ${latest.ref} · ${latest.commitSha.take(8)}",
                                        color = LocalMuted,
                                        fontSize = 11.sp,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            error = ""
                                            pendingRollback = latest
                                        },
                                        enabled = !busy && site.status == LocalHostingStore.STATUS_STOPPED,
                                    ) { Text("Rollback latest deployment") }
                                }
                                if (site.status == LocalHostingStore.STATUS_ACTIVE) {
                                    Text(
                                        "http://${if (allowLan && phoneAddress != null) phoneAddress else "127.0.0.1"}:${LocalStaticServer.PORT}/s/${site.slug}/",
                                        color = LocalBlue,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }

                    if (events.isNotEmpty()) item {
                        Text("Recent local activity", color = LocalNavy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    items(events.take(10)) { event ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(event.action.replace('.', ' '), color = LocalNavy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(event.detail, DateFormat.getDateTimeInstance().format(Date(event.createdAt)))
                                    .filter(String::isNotBlank).joinToString(" · "),
                                color = LocalMuted,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create local website") },
            text = {
                OutlinedTextField(
                    value = siteName,
                    onValueChange = { if (it.length <= 80) siteName = it },
                    label = { Text("Website name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            try {
                                withContext(Dispatchers.IO) { store.createSite(siteName) }
                                showCreate = false
                                siteName = ""
                                reload()
                            } catch (failure: Exception) {
                                error = failure.message ?: "Website could not be created."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && siteName.trim().length in 2..80,
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }

    if (showGithubDeployment) {
        val selectedTargetSite = targetSiteId?.let { id -> sites.firstOrNull { it.id == id } }
        val targetSiteMissing = targetSiteId != null && selectedTargetSite == null
        AlertDialog(
            onDismissRequest = { if (!busy) showGithubDeployment = false },
            title = { Text(if (selectedTargetSite == null) "Deploy a public GitHub site" else "Update ${selectedTargetSite.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (selectedTargetSite == null) {
                            "Internet is needed for the download. Choose a branch, tag, or commit; blank uses the default branch. The deployment records the resolved commit SHA and publishes a prebuilt folder containing index.html. This phone does not build source code yet."
                        } else {
                            "This replaces ${selectedTargetSite.name}'s files after creating a rollback backup. Keep the site stopped during the update. Each backup is limited to 50 MiB; up to three snapshots are kept."
                        },
                        color = LocalMuted,
                        fontSize = 12.sp,
                    )
                    if (targetSiteMissing) {
                        Text("The selected website no longer exists. Close this dialog and choose another site.", color = Color(0xFFB42318), fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = githubRepositoryUrl,
                        onValueChange = { if (it.length <= 300) githubRepositoryUrl = it },
                        label = { Text("Public GitHub repository URL") },
                        placeholder = { Text("https://github.com/owner/repository") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = githubRef,
                        onValueChange = { if (it.length <= 255) githubRef = it },
                        label = { Text("Branch, tag or commit (optional)") },
                        placeholder = { Text("main or commit SHA") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    if (selectedTargetSite == null) {
                        OutlinedTextField(
                            value = githubSiteName,
                            onValueChange = { if (it.length <= 80) githubSiteName = it },
                            label = { Text("Website name") },
                            singleLine = true,
                            enabled = !busy,
                        )
                    }
                    OutlinedTextField(
                        value = githubPublishDirectory,
                        onValueChange = { if (it.length <= 200) githubPublishDirectory = it },
                        label = { Text("Prebuilt folder (optional)") },
                        placeholder = { Text("dist or docs") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp))
                    if (error.isNotBlank()) Text(error, color = Color(0xFFB42318), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { deployGithubSite() },
                    enabled = !busy && !targetSiteMissing && githubRepositoryUrl.isNotBlank() &&
                        (selectedTargetSite != null || githubSiteName.trim().length in 2..80),
                ) { Text(if (busy) "Deploying…" else "Download and deploy") }
            },
            dismissButton = {
                TextButton(onClick = { showGithubDeployment = false }, enabled = !busy) { Text("Cancel") }
            },
        )
    }

    pendingRollback?.let { deployment ->
        val siteName = sites.firstOrNull { it.id == deployment.siteId }?.name ?: "this website"
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRollback = null },
            title = { Text("Roll back deployment?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Restore the saved files from before ${deployment.repository} · ${deployment.ref} · ${deployment.commitSha.take(8)} was deployed to $siteName? The website must stay stopped.",
                        color = LocalMuted,
                        fontSize = 13.sp,
                    )
                    if (error.isNotBlank()) Text(error, color = Color(0xFFB42318), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { rollbackDeployment(deployment) }, enabled = !busy) {
                    Text(if (busy) "Restoring…" else "Restore previous files")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollback = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${site.name}?") },
            text = { Text("This deletes the local site files from this phone.") },
            confirmButton = {
                TextButton(onClick = { deleteSite(site) }, enabled = !busy) { Text("Delete", color = Color(0xFFB42318)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(label, color = LocalMuted, fontSize = 10.sp)
            Text(value, color = LocalNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
