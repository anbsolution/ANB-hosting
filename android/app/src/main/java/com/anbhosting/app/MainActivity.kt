package com.anbhosting.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenResumed
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Navy = Color(0xFF101C3A)
private val Blue = Color(0xFF3157D5)
private val Page = Color(0xFFF4F6FB)
private val Muted = Color(0xFF718096)
private val Green = Color(0xFF16865B)

class MainActivity : ComponentActivity() {
    private var pendingPermissionCallbacks: Pair<() -> Unit, () -> Unit>? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callbacks = pendingPermissionCallbacks
        pendingPermissionCallbacks = null
        if (granted) callbacks?.first?.invoke() else callbacks?.second?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalHostingStore(applicationContext)
        setContent {
            HostingApp(
                api = HostingApi(),
                localStore = store,
                onRequestHostingPermission = ::requestHostingPermission,
                onStartHosting = { startLocalHostingService() },
                onStopHosting = { stopService(Intent(this, LocalHostingService::class.java)) },
                onReconfigureHosting = {
                    startLocalHostingService(LocalHostingService.ACTION_RECONFIGURE)
                },
                onOpenPreview = ::openPreview,
            )
        }
        lifecycleScope.launch {
            val hasActiveLocalSites = withContext(Dispatchers.IO) {
                store.listSites().any { it.status == LocalHostingStore.STATUS_ACTIVE }
            }
            if (hasActiveLocalSites && !LocalHostingService.serverRunning) {
                lifecycle.whenResumed {
                    requestHostingPermission(
                        onGranted = { startLocalHostingService() },
                        onDenied = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                store.listSites().filter { it.status == LocalHostingStore.STATUS_ACTIVE }
                                    .forEach { store.setSiteStatus(it.id, LocalHostingStore.STATUS_STOPPED) }
                            }
                            stopService(Intent(this@MainActivity, LocalHostingService::class.java))
                            Toast.makeText(this@MainActivity, "Local hosting was stopped because notifications are disabled.", Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
    }

    private fun requestHostingPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            return
        }
        pendingPermissionCallbacks = onGranted to onDenied
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startLocalHostingService(action: String? = null) {
        val intent = Intent(this, LocalHostingService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun openPreview(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is available to preview this site.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun HostingApp(
    api: HostingApi,
    localStore: LocalHostingStore,
    onRequestHostingPermission: (onGranted: () -> Unit, onDenied: () -> Unit) -> Unit,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit,
    onReconfigureHosting: () -> Unit,
    onOpenPreview: (String) -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var localMode by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var stats by remember { mutableStateOf(JSONObject()) }
    var sites by remember { mutableStateOf(JSONArray()) }
    var tab by remember { mutableStateOf("Overview") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = ""
            try { block() } catch (e: Exception) { error = e.message ?: "Request failed" }
            finally { busy = false }
        }
    }
    suspend fun reload() {
        val p = withContext(Dispatchers.IO) { api.profile() }
        val s = withContext(Dispatchers.IO) { api.summary() }
        val w = withContext(Dispatchers.IO) { api.websites() }
        profile = p; stats = s; sites = w; signedIn = true
    }

    MaterialTheme(
        colorScheme = lightColorScheme(primary = Blue, background = Page, surface = Color.White),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            when {
                localMode -> LocalHostingScreen(
                    store = localStore,
                    onBack = { localMode = false },
                    onStartHosting = onStartHosting,
                    onStopHosting = onStopHosting,
                    onReconfigureHosting = onReconfigureHosting,
                    onRequestHostingPermission = onRequestHostingPermission,
                    onOpenPreview = onOpenPreview,
                )
                !configured -> ServerScreen(server, { server = it }, busy, error, onOpenLocal = { localMode = true }) {
                    task { api.setServer(server); if (server.isBlank()) throw IllegalArgumentException("Enter the server URL") else configured = true }
                }
                !signedIn -> AuthScreen(register, { register = it }, email, { email = it }, password, { password = it }, workspace, { workspace = it }, busy, error,
                    submit = { task {
                        withContext(Dispatchers.IO) {
                            if (register) api.register(email, password, workspace) else api.login(email, password)
                        }
                        reload()
                    } }, changeServer = { configured = false; error = "" })
                else -> HomeScreen(profile, stats, sites, tab, { tab = it }, busy, error,
                    refresh = { task { reload() } }, action = { id, action -> task {
                        withContext(Dispatchers.IO) { api.websiteAction(id, action) }; reload()
                    } },
                    openLocalHosting = { localMode = true },
                    logout = { task { withContext(Dispatchers.IO) { api.logout() }; signedIn = false; profile = null; password = "" } },
                    changeServer = { configured = false; signedIn = false })
            }
        }
    }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(54.dp).background(
                Brush.linearGradient(listOf(Navy, Color(0xFF263F85))),
                RoundedCornerShape(17.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(5.dp).size(10.dp)
                    .background(Color(0xFF6D94FF), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("ANB HOSTING", color = Navy, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            Text("Your sites. Your control.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun ServerScreen(
    value: String,
    change: (String) -> Unit,
    busy: Boolean,
    error: String,
    onOpenLocal: () -> Unit,
    next: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Brand()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CONTROL PLANE", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("Connect your server", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("Use the trusted HTTPS address of your ANB Hosting server.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFE4EAF5)),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = change,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server URL") },
                    placeholder = { Text("https://192.168.1.10:8443") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Text("Install the server's trusted CA certificate on this phone before connecting to a local HTTPS server.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                ErrorMessage(error)
                PrimaryButton("Continue", busy, next)
            }
        }
        OutlinedButton(
            onClick = onOpenLocal,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Host on this phone", fontWeight = FontWeight.Bold)
                Text("Offline local hosting", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun AuthScreen(register: Boolean, mode: (Boolean) -> Unit, email: String, emailChange: (String) -> Unit,
    password: String, passwordChange: (String) -> Unit, workspace: String, workspaceChange: (String) -> Unit,
    busy: Boolean, error: String, submit: () -> Unit, changeServer: () -> Unit) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Brand()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (register) "GET STARTED" else "SECURE SIGN IN", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(if (register) "Create your workspace" else "Welcome back", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(if (register) "Set up a workspace to manage your hosting." else "Sign in to manage your websites and hosting.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFE4EAF5)),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (register) {
                    OutlinedTextField(
                        workspace,
                        workspaceChange,
                        Modifier.fillMaxWidth(),
                        label = { Text("Workspace name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                OutlinedTextField(
                    email,
                    emailChange,
                    Modifier.fillMaxWidth(),
                    label = { Text("Email address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    password,
                    passwordChange,
                    Modifier.fillMaxWidth(),
                    label = { Text("Password (12+ characters)") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                ErrorMessage(error)
                PrimaryButton(if (register) "Create account" else "Sign in", busy, submit)
            }
        }
        TextButton(onClick = { mode(!register) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "Already registered? Sign in" else "Create an account")
        }
        TextButton(onClick = changeServer, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
    }
}

@Composable private fun HomeScreen(profile: JSONObject?, stats: JSONObject, websites: JSONArray, tab: String, setTab: (String) -> Unit,
    busy: Boolean, error: String, refresh: () -> Unit, action: (String, String) -> Unit,
    openLocalHosting: () -> Unit, logout: () -> Unit, changeServer: () -> Unit) {
    val tenant = profile?.optJSONObject("tenant")?.optString("name").orEmpty().ifBlank { "Workspace" }
    val email = profile?.optJSONObject("user")?.optString("email").orEmpty()
    Column(Modifier.fillMaxSize().background(Page)) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Navy, Color(0xFF1C3472), Color(0xFF263F85))),
                RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            ).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Blue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("ANB HOSTING", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text(tenant, color = Color(0xFFD2DCF4), fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = refresh, enabled = !busy) { Text("Refresh", color = Color.White) }
            }
            Spacer(Modifier.height(18.dp))
            Text("Hosting overview", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(email, color = Color(0xFFB9C7E7), fontSize = 12.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("Overview", "Websites", "Account").forEach { item ->
                TextButton(
                    onClick = { setTab(item) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (tab == item) Color(0xFFE7EDFF) else Color.Transparent,
                        contentColor = if (tab == item) Blue else Muted,
                    ),
                ) { Text(item, fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = openLocalHosting),
            color = Navy,
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Color(0xFF263F85), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Host on this phone", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Offline hosting · files stay on device", color = Color(0xFFD2DCF4), fontSize = 11.sp)
                }
                Text("OPEN  ›", color = Color(0xFFB9C7E7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorMessage(error, Modifier.padding(horizontal = 16.dp))
        when (tab) {
            "Overview" -> Overview(stats, websites, busy, action)
            "Websites" -> WebsiteList(websites, busy, action)
            else -> Account(tenant, email, changeServer, logout)
        }
    }
}

@Composable private fun Overview(stats: JSONObject, websites: JSONArray, busy: Boolean, action: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your workspace", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Metric("Websites", stats.optInt("websites"), Modifier.weight(1f)); Metric("Active", stats.optInt("activeWebsites"), Modifier.weight(1f), Green) } }
        item { Metric("Domains", stats.optInt("domains"), Modifier.fillMaxWidth()) }
        item { Text("Recent websites", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy) }
        val recent = (0 until minOf(websites.length(), 3)).mapNotNull { websites.optJSONObject(it) }
        if (recent.isEmpty()) item { EmptyWebsitesCard() }
        items(recent) { site -> SiteCard(site, busy, action) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF3FC)), shape = RoundedCornerShape(16.dp)) {
                Text("Storage and bandwidth are not measured by the current server build.", Modifier.fillMaxWidth().padding(14.dp), color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun WebsiteList(websites: JSONArray, busy: Boolean, action: (String, String) -> Unit) {
    val data = (0 until websites.length()).mapNotNull { websites.optJSONObject(it) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Websites", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item { Text("Manage status for the sites in this workspace.", color = Muted, fontSize = 13.sp) }
        if (data.isEmpty()) item { EmptyWebsitesCard() }
        items(data) { SiteCard(it, busy, action) }
    }
}

@Composable private fun SiteCard(site: JSONObject, busy: Boolean, action: (String, String) -> Unit) {
    val id = site.optString("id"); val status = site.optString("status", "UNKNOWN")
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFFE4EAF5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.optString("name", "Website"), color = Navy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(site.optString("hostname"), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                LocalStatusPill(
                    status,
                    color = if (status == "ACTIVE") Green else if (status == "FAILED") Color(0xFFB42318) else Muted,
                    background = if (status == "ACTIVE") Color(0xFFE9F7F0) else if (status == "FAILED") Color(0xFFFFEEEC) else Color(0xFFF0F2F7),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "ACTIVE" -> {
                        OutlinedButton(onClick = { action(id, "stop") }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Stop") }
                        OutlinedButton(onClick = { action(id, "restart") }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Restart") }
                    }
                    "STOPPED", "FAILED" -> Button(onClick = { action(id, "start") }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("Start") }
                }
            }
        }
    }
}

@Composable private fun EmptyWebsitesCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFFE4EAF5)),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your first site is waiting", color = Navy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Create a website in the ANB Hosting dashboard to see it here.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun Account(tenant: String, email: String, changeServer: () -> Unit, logout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Account", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Workspace and connection settings", color = Muted, fontSize = 13.sp)
        Metric("Workspace", tenant, Modifier.fillMaxWidth()); Metric("Signed in as", email, Modifier.fillMaxWidth())
        OutlinedButton(onClick = changeServer, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Change server") }
        OutlinedButton(onClick = logout, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Sign out") }
    }
}

@Composable private fun Metric(label: String, value: Any, modifier: Modifier = Modifier, tint: Color = Navy) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE4EAF5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(value.toString(), color = tint, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
    }
}

@Composable private fun PrimaryButton(label: String, busy: Boolean, click: () -> Unit) {
    Button(onClick = click, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp) else Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    if (message.isNotBlank()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0EE)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFFF0C6C2)),
        ) {
            Text(message, Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFFB42318), fontSize = 13.sp)
        }
    }
}
