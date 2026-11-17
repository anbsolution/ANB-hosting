package com.anbhosting.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
    val aliasesBySite: Map<String, List<LocalHostAlias>>,
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
    val databaseManager = remember(context.applicationContext, store) {
        LocalDatabaseManager(context.applicationContext, store)
    }
    val githubDeployer = remember(context.applicationContext, store, fileManager) {
        LocalGithubDeployer(context.applicationContext, store, fileManager)
    }
    val resourceMonitor = remember(context) { LocalResourceMonitor(context) }
    var sites by remember { mutableStateOf(emptyList<LocalSite>()) }
    var usageBySite by remember { mutableStateOf(emptyMap<String, LocalSiteUsage>()) }
    var deviceMetrics by remember { mutableStateOf(LocalDeviceMetrics(0L, 0L, null)) }
    var events by remember { mutableStateOf(emptyList<LocalHostingEvent>()) }
    var deploymentsBySite by remember { mutableStateOf(emptyMap<String, LocalDeployment>()) }
    var aliasesBySite by remember { mutableStateOf(emptyMap<String, List<LocalHostAlias>>()) }
    var allowLan by remember { mutableStateOf(store.isLanSharingEnabled()) }
    var keepCpuAwake by remember { mutableStateOf(store.isKeepCpuAwakeEnabled()) }
    var siteSearch by remember { mutableStateOf("") }
    var siteStatusFilter by remember { mutableStateOf("All") }
    var siteSort by remember { mutableStateOf("Newest") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
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
    var localDomainSite by remember { mutableStateOf<LocalSite?>(null) }
    var localDomainLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var phoneAddress by remember { mutableStateOf(LocalNetwork.wifiIpv4Address(context)?.hostAddress) }
    var managedSite by remember { mutableStateOf<LocalSite?>(null) }
    var databaseSite by remember { mutableStateOf<LocalSite?>(null) }
    var logsSite by remember { mutableStateOf<LocalSite?>(null) }
    var previewUrl by remember { mutableStateOf<String?>(null) }

    BackHandler(
        enabled = managedSite == null && databaseSite == null && logsSite == null && previewUrl == null,
        onBack = onBack,
    )

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
                        aliasesBySite = allSites.associate { site ->
                            site.id to store.listLocalHostAliases(site.id)
                        },
                        deviceMetrics = resourceMonitor.sample(),
                    )
                }
                sites = result.sites
                events = result.events
                usageBySite = result.usageBySite
                deploymentsBySite = result.deploymentsBySite
                aliasesBySite = result.aliasesBySite
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

    val matchingSites = sites.filter { site ->
        val matchesSearch = siteSearch.isBlank() || listOf(site.name, site.slug, site.localHostname)
            .any { it.contains(siteSearch.trim(), ignoreCase = true) }
        val matchesStatus = when (siteStatusFilter) {
            "Running" -> site.status == LocalHostingStore.STATUS_ACTIVE
            "Stopped" -> site.status == LocalHostingStore.STATUS_STOPPED
            else -> true
        }
        matchesSearch && matchesStatus
    }
    val visibleSites = when (siteSort) {
        "Oldest" -> matchingSites.sortedBy { it.createdAt }
        "Name A–Z" -> matchingSites.sortedBy { it.name.lowercase() }
        "Running first" -> matchingSites.sortedWith(
            compareBy<LocalSite> { it.status != LocalHostingStore.STATUS_ACTIVE }
                .thenByDescending { it.createdAt },
        )
        else -> matchingSites.sortedByDescending { it.createdAt }
    }

    fun startSite(site: LocalSite) {
        onRequestHostingPermission(
            {
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
            { error = "Notification permission is needed to keep local hosting visible while it runs." },
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
                    databaseManager.deleteAllForSite(site.id)
                    store.deleteSite(site.id)
                    store.listSites().any { it.status == LocalHostingStore.STATUS_ACTIVE }
                }
                if (!remaining) onStopHosting()
                if (localDomainSite?.id == site.id) localDomainSite = null
                if (databaseSite?.id == site.id) databaseSite = null
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
                siteSearch = ""
                siteStatusFilter = "All"
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

    fun addLocalHostname(site: LocalSite) {
        scope.launch {
            busy = true
            error = ""
            try {
                withContext(Dispatchers.IO) {
                    store.createLocalHostAlias(site.id, localDomainLabel)
                }
                localDomainLabel = ""
                reload()
            } catch (failure: Exception) {
                error = failure.message ?: "Local hostname could not be added."
            } finally {
                busy = false
            }
        }
    }

    fun removeLocalHostname(alias: LocalHostAlias) {
        scope.launch {
            busy = true
            error = ""
            try {
                withContext(Dispatchers.IO) {
                    store.deleteLocalHostAlias(alias.siteId, alias.id)
                }
                reload()
            } catch (failure: Exception) {
                error = failure.message ?: "Local hostname could not be removed."
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
            } else if (databaseSite != null) {
                LocalDatabaseManagerScreen(
                    manager = databaseManager,
                    site = requireNotNull(databaseSite),
                    onBack = { databaseSite = null; reload() },
                )
            } else if (logsSite != null) {
                LocalWebsiteLogsScreen(
                    store = store,
                    site = requireNotNull(logsSite),
                    onBack = { logsSite = null; reload() },
                )
            } else if (previewUrl != null) {
                LocalWebsitePreviewScreen(
                    url = requireNotNull(previewUrl),
                    onBack = { previewUrl = null; reload() },
                    onOpenBrowser = onOpenPreview,
                )
            } else Column(Modifier.fillMaxSize()) {
                LocalHeroHeader(
                    eyebrow = "ANB HOSTING · ON DEVICE",
                    title = "Host on this phone",
                    subtitle = "Your sites and settings stay on this device, ready for offline preview.",
                    backLabel = "Back",
                    onBack = onBack,
                    actions = {
                        TextButton(onClick = { reload() }) { Text("Refresh", color = Color.White) }
                        LocalStatusPill("LOCAL", LocalGreen, Color(0xFF173D43))
                    },
                )

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LocalMetricCard("ANB RAM", formatBytes(deviceMetrics.appMemoryBytes), Modifier.weight(1f))
                            LocalMetricCard(
                                "App CPU",
                                deviceMetrics.appCpuPercent?.let { "${"%.1f".format(it)}%" } ?: "Unavailable",
                                Modifier.weight(1f),
                            )
                            LocalMetricCard("Free storage", formatBytes(deviceMetrics.freeStorageBytes), Modifier.weight(1f))
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Local websites", color = LocalNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                val runningCount = sites.count { it.status == LocalHostingStore.STATUS_ACTIVE }
                                Text(
                                    if (siteSearch.isBlank() && siteStatusFilter == "All") {
                                        "${sites.size} saved · $runningCount running"
                                    } else {
                                        "${visibleSites.size} of ${sites.size} sites · $runningCount running"
                                    },
                                    color = LocalMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            Button(
                                onClick = {
                                    siteSearch = ""
                                    siteStatusFilter = "All"
                                    siteName = ""
                                    showCreate = true
                                },
                                shape = RoundedCornerShape(16.dp),
                            ) { Text("New site") }
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
                            shape = RoundedCornerShape(16.dp),
                        ) { Text("Deploy public GitHub site") }
                    }

                    if (sites.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = siteSearch,
                                onValueChange = { siteSearch = it.take(80) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Search sites, hostnames") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                trailingIcon = {
                                    if (siteSearch.isNotBlank()) {
                                        TextButton(onClick = { siteSearch = "" }) { Text("Clear") }
                                    }
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("All", "Running", "Stopped").forEach { filter ->
                                    FilterChip(
                                        selected = siteStatusFilter == filter,
                                        onClick = { siteStatusFilter = filter },
                                        label = { Text(filter, maxLines = 1) },
                                        shape = RoundedCornerShape(14.dp),
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Box {
                                    TextButton(onClick = { sortMenuExpanded = true }) { Text("Sort · $siteSort") }
                                    DropdownMenu(
                                        expanded = sortMenuExpanded,
                                        onDismissRequest = { sortMenuExpanded = false },
                                    ) {
                                        listOf("Newest", "Oldest", "Name A–Z", "Running first").forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                    siteSort = option
                                                    sortMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (error.isNotBlank()) item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F0)),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Color(0xFFF4C8C5)),
                        ) {
                            Text(error, Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFFB42318), fontSize = 13.sp)
                        }
                    }

                    if (sites.isEmpty()) item {
                        LocalPremiumCard {
                            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No local sites yet", color = LocalNavy, fontWeight = FontWeight.Bold)
                                Text("Create a site, then start it to preview the starter page offline.", color = LocalMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    if (sites.isNotEmpty() && visibleSites.isEmpty()) item {
                        LocalPremiumCard {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("No matching websites", color = LocalNavy, fontWeight = FontWeight.Bold)
                                Text("Try another search or status filter.", color = LocalMuted, fontSize = 12.sp)
                                TextButton(
                                    onClick = {
                                        siteSearch = ""
                                        siteStatusFilter = "All"
                                    },
                                ) { Text("Clear search and filters") }
                            }
                        }
                    }

                    items(visibleSites, key = { it.id }) { site ->
                        val deployment = deploymentsBySite[site.id]
                        val siteUrl = if (allowLan && phoneAddress != null) {
                            "http://$phoneAddress:${LocalStaticServer.PORT}/s/${site.slug}/"
                        } else {
                            "http://${site.localHostname}:${LocalStaticServer.PORT}/"
                        }
                        LocalPremiumCard {
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
                                    val running = site.status == LocalHostingStore.STATUS_ACTIVE
                                    LocalStatusPill(
                                        text = if (running) "RUNNING" else "STOPPED",
                                        color = if (running) LocalGreen else LocalMuted,
                                        background = if (running) Color(0xFFE9F7F0) else Color(0xFFF0F2F7),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (site.status == LocalHostingStore.STATUS_ACTIVE) {
                                        OutlinedButton(
                                            onClick = {
                                                previewUrl = "http://${site.localHostname}:${LocalStaticServer.PORT}/"
                                            },
                                            enabled = !busy,
                                            shape = RoundedCornerShape(14.dp),
                                        ) { Text("Preview") }
                                        OutlinedButton(onClick = { stopSite(site) }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Stop") }
                                    } else {
                                        Button(onClick = { startSite(site) }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Start hosting") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { managedSite = site }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Files") }
                                    OutlinedButton(onClick = { databaseSite = site }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Database") }
                                    OutlinedButton(onClick = { logsSite = site }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Logs") }
                                }
                                Row {
                                    TextButton(onClick = { pendingDelete = site }, enabled = !busy) {
                                        Text("Delete website", color = Color(0xFFB42318))
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        localDomainSite = site
                                        localDomainLabel = ""
                                        error = ""
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("Manage local domains (${(aliasesBySite[site.id]?.size ?: 0) + 1})") }
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
                                        shape = RoundedCornerShape(14.dp),
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
                                        shape = RoundedCornerShape(14.dp),
                                    ) { Text("Rollback latest deployment") }
                                }
                                if (site.status == LocalHostingStore.STATUS_ACTIVE) {
                                    Text(siteUrl, color = LocalBlue, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    item {
                        LocalPremiumCard {
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
                                    Text(
                                        "Each site gets a phone-only .localhost subdomain for offline preview.",
                                        color = LocalMuted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        LocalPremiumCard {
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
                                siteSearch = ""
                                siteStatusFilter = "All"
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

    localDomainSite?.let { site ->
        val aliases = aliasesBySite[site.id].orEmpty()
        AlertDialog(
            onDismissRequest = { if (!busy) localDomainSite = null },
            title = { Text("Local domains · ${site.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "These .localhost names route to this website on this phone, including while offline. They are not public domains and do not enable HTTPS.",
                        color = LocalMuted,
                        fontSize = 12.sp,
                    )
                    Text("Default hostname", color = LocalNavy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(site.localHostname, color = LocalBlue, fontSize = 12.sp)
                    aliases.forEach { alias ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(alias.hostname, color = LocalNavy, fontSize = 12.sp)
                                Text("Offline phone alias", color = LocalMuted, fontSize = 10.sp)
                            }
                            TextButton(
                                onClick = { removeLocalHostname(alias) },
                                enabled = !busy,
                            ) { Text("Remove", color = Color(0xFFB42318)) }
                            TextButton(
                                onClick = {
                                    localDomainSite = null
                                    previewUrl = "http://${alias.hostname}:${LocalStaticServer.PORT}/"
                                },
                                enabled = !busy && site.status == LocalHostingStore.STATUS_ACTIVE,
                            ) { Text("Open") }
                        }
                    }
                    OutlinedTextField(
                        value = localDomainLabel,
                        onValueChange = { if (it.length <= 60) localDomainLabel = it },
                        label = { Text("New local hostname") },
                        placeholder = { Text("shop, then .localhost is added") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    if (localDomainLabel.isNotBlank()) {
                        Text("Preview: ${localDomainLabel.trim().lowercase()}.localhost", color = LocalBlue, fontSize = 11.sp)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp))
                    if (error.isNotBlank()) Text(error, color = Color(0xFFB42318), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { addLocalHostname(site) },
                    enabled = !busy && localDomainLabel.isNotBlank(),
                ) { Text(if (busy) "Saving…" else "Add local domain") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) localDomainSite = null }, enabled = !busy) { Text("Done") }
            },
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
