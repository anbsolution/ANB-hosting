package com.anbhosting.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HostingApp(HostingApi()) }
    }
}

@Composable
private fun HostingApp(api: HostingApi) {
    var server by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
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

    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Page, surface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            when {
                !configured -> ServerScreen(server, { server = it }, busy, error) {
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
                    logout = { task { withContext(Dispatchers.IO) { api.logout() }; signedIn = false; profile = null; password = "" } },
                    changeServer = { configured = false; signedIn = false })
            }
        }
    }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(Navy, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text("A", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("ANB HOSTING", color = Navy, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Hosting, under your control", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun ServerScreen(value: String, change: (String) -> Unit, busy: Boolean, error: String, next: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Brand(); Spacer(Modifier.height(32.dp))
        Text("Connect your server", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Use the HTTPS address of your ANB Hosting server on your trusted network.", color = Muted)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text("Server URL") }, placeholder = { Text("https://192.168.1.10:8443") }, singleLine = true)
        Text("For a local server, install its trusted CA certificate on this Android device first.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        ErrorMessage(error); Spacer(Modifier.height(16.dp)); PrimaryButton("Continue", busy, next)
    }
}

@Composable private fun AuthScreen(register: Boolean, mode: (Boolean) -> Unit, email: String, emailChange: (String) -> Unit,
    password: String, passwordChange: (String) -> Unit, workspace: String, workspaceChange: (String) -> Unit,
    busy: Boolean, error: String, submit: () -> Unit, changeServer: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Brand(); Spacer(Modifier.height(28.dp))
        Text(if (register) "Create your workspace" else "Welcome back", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text(if (register) "Start managing your hosted websites." else "Sign in to manage your hosting.", color = Muted)
        Spacer(Modifier.height(18.dp))
        if (register) { OutlinedTextField(workspace, workspaceChange, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, singleLine = true); Spacer(Modifier.height(9.dp)) }
        OutlinedTextField(email, emailChange, Modifier.fillMaxWidth(), label = { Text("Email address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
        Spacer(Modifier.height(9.dp))
        OutlinedTextField(password, passwordChange, Modifier.fillMaxWidth(), label = { Text("Password (12+ characters)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        ErrorMessage(error); Spacer(Modifier.height(16.dp)); PrimaryButton(if (register) "Create account" else "Sign in", busy, submit)
        TextButton(onClick = { mode(!register) }, modifier = Modifier.fillMaxWidth()) { Text(if (register) "Already registered? Sign in" else "Create an account") }
        TextButton(onClick = changeServer, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
    }
}

@Composable private fun HomeScreen(profile: JSONObject?, stats: JSONObject, websites: JSONArray, tab: String, setTab: (String) -> Unit,
    busy: Boolean, error: String, refresh: () -> Unit, action: (String, String) -> Unit, logout: () -> Unit, changeServer: () -> Unit) {
    val tenant = profile?.optJSONObject("tenant")?.optString("name").orEmpty().ifBlank { "Workspace" }
    val email = profile?.optJSONObject("user")?.optString("email").orEmpty()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Navy).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) { Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text("ANB HOSTING", color = Color.White, fontWeight = FontWeight.Bold); Text(tenant, color = Color(0xFFB9C7E7), fontSize = 12.sp) }
                TextButton(onClick = refresh) { Text("Refresh", color = Color.White) }
            }
            Spacer(Modifier.height(14.dp)); Text("Control your cloud.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold); Text(email, color = Color(0xFFB9C7E7), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().background(Color.White), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Overview", "Websites", "Account").forEach { item -> TextButton(onClick = { setTab(item) }) { Text(item, color = if (tab == item) Blue else Muted, fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Normal) } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorMessage(error, Modifier.padding(horizontal = 16.dp))
        when (tab) {
            "Overview" -> Overview(stats, websites)
            "Websites" -> WebsiteList(websites, action)
            else -> Account(tenant, email, changeServer, logout)
        }
    }
}

@Composable private fun Overview(stats: JSONObject, websites: JSONArray) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Overview", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Metric("Websites", stats.optInt("websites"), Modifier.weight(1f)); Metric("Active", stats.optInt("activeWebsites"), Modifier.weight(1f), Green) } }
        item { Metric("Domains", stats.optInt("domains"), Modifier.fillMaxWidth()) }
        item { Text("Recent websites", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy) }
        items((0 until minOf(websites.length(), 3)).mapNotNull { websites.optJSONObject(it) }) { SiteCard(it) { _, _ -> } }
        item { Text("Storage and bandwidth are not measured by the current server build.", color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun WebsiteList(websites: JSONArray, action: (String, String) -> Unit) {
    val data = (0 until websites.length()).mapNotNull { websites.optJSONObject(it) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Websites", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        if (data.isEmpty()) item { Text("No websites yet. Create your first website from the web dashboard.", color = Muted) }
        items(data) { SiteCard(it, action) }
    }
}

@Composable private fun SiteCard(site: JSONObject, action: (String, String) -> Unit) {
    val id = site.optString("id"); val status = site.optString("status", "UNKNOWN")
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(site.optString("name", "Website"), color = Navy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(site.optString("hostname"), color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp))
            Text(status, color = if (status == "ACTIVE") Green else Blue, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "ACTIVE" -> { OutlinedButton(onClick = { action(id, "stop") }) { Text("Stop") }; OutlinedButton(onClick = { action(id, "restart") }) { Text("Restart") } }
                    "STOPPED", "FAILED" -> OutlinedButton(onClick = { action(id, "start") }) { Text("Start") }
                }
            }
        }
    }
}

@Composable private fun Account(tenant: String, email: String, changeServer: () -> Unit, logout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Account", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
        Metric("Workspace", tenant, Modifier.fillMaxWidth()); Metric("Signed in as", email, Modifier.fillMaxWidth())
        OutlinedButton(onClick = changeServer, modifier = Modifier.fillMaxWidth()) { Text("Change server") }
        OutlinedButton(onClick = logout, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable private fun Metric(label: String, value: Any, modifier: Modifier = Modifier, tint: Color = Navy) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) { Text(label, color = Muted, fontSize = 12.sp); Text(value.toString(), color = tint, fontWeight = FontWeight.Bold, fontSize = 23.sp) }
    }
}

@Composable private fun PrimaryButton(label: String, busy: Boolean, click: () -> Unit) {
    Button(onClick = click, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp) else Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    if (message.isNotBlank()) Text(message, modifier.padding(top = 9.dp), color = Color(0xFFB42318), fontSize = 13.sp)
}
