package com.anbhosting.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    var sites by remember { mutableStateOf(emptyList<LocalSite>()) }
    var events by remember { mutableStateOf(emptyList<LocalHostingEvent>()) }
    var allowLan by remember { mutableStateOf(store.isLanSharingEnabled()) }
    var siteName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LocalSite?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var phoneAddress by remember { mutableStateOf(LocalNetwork.wifiIpv4Address(context)?.hostAddress) }
    var managedSite by remember { mutableStateOf<LocalSite?>(null) }

    BackHandler(enabled = managedSite == null, onBack = onBack)

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                store.listSites() to store.recentEvents()
            }
            sites = result.first
            events = result.second
            allowLan = store.isLanSharingEnabled()
            phoneAddress = LocalNetwork.wifiIpv4Address(context)?.hostAddress
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
                        Text("OFFLINE MODE", color = Color(0xFFB9C7E7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Local websites", color = LocalNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("${sites.size} saved · ${sites.count { it.status == LocalHostingStore.STATUS_ACTIVE }} running", color = LocalMuted, fontSize = 12.sp)
                            }
                            Button(onClick = { siteName = ""; showCreate = true }) { Text("New site") }
                        }
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
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, color = LocalNavy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(site.slug, color = LocalMuted, fontSize = 12.sp)
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
