package com.anbhosting.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalDatabaseManagerScreen(
    manager: LocalDatabaseManager,
    site: LocalSite,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var databases by remember(site.id) { mutableStateOf(emptyList<LocalManagedDatabase>()) }
    var selectedDatabase by remember(site.id) { mutableStateOf<String?>(null) }
    var sql by remember(site.id) { mutableStateOf(DEFAULT_SCHEMA_QUERY) }
    var queryResult by remember(site.id) { mutableStateOf<LocalSqlResult?>(null) }
    var error by remember(site.id) { mutableStateOf("") }
    var busy by remember(site.id) { mutableStateOf(false) }
    var showCreateDialog by remember(site.id) { mutableStateOf(false) }
    var newDatabaseName by remember(site.id) { mutableStateOf("") }
    var pendingImport by remember(site.id) { mutableStateOf<Uri?>(null) }
    var importName by remember(site.id) { mutableStateOf("") }
    var pendingDelete by remember(site.id) { mutableStateOf<String?>(null) }
    var pendingExport by remember(site.id) { mutableStateOf<String?>(null) }

    suspend fun reloadDatabases() {
        databases = withContext(Dispatchers.IO) { manager.list(site.id) }
    }

    suspend fun openDatabase(name: String) {
        selectedDatabase = name
        sql = DEFAULT_SCHEMA_QUERY
        queryResult = withContext(Dispatchers.IO) { manager.execute(site.id, name, DEFAULT_SCHEMA_QUERY) }
    }

    fun refresh() {
        scope.launch {
            try {
                reloadDatabases()
                error = ""
            } catch (failure: Exception) {
                error = failure.message ?: "Databases could not be loaded."
            }
        }
    }

    fun runQuery(databaseName: String, statement: String = sql) {
        scope.launch {
            busy = true
            error = ""
            queryResult = null
            try {
                queryResult = withContext(Dispatchers.IO) { manager.execute(site.id, databaseName, statement) }
                reloadDatabases()
            } catch (failure: Exception) {
                error = failure.message ?: "SQL statement failed."
            } finally {
                busy = false
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "imported.db"
            pendingImport = uri
            importName = suggestDatabaseName(displayName)
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
    ) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) {
            scope.launch {
                busy = true
                error = ""
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("The export destination could not be opened.")
                        manager.export(site.id, name, output)
                    }
                    error = "Database exported successfully."
                } catch (failure: Exception) {
                    error = failure.message ?: "Database export failed."
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(site.id) { refresh() }
    BackHandler {
        if (selectedDatabase != null) {
            selectedDatabase = null
            queryResult = null
            error = ""
        } else {
            onBack()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LocalHeroHeader(
            eyebrow = "LOCAL DATA · ON DEVICE",
            title = selectedDatabase ?: "Databases · ${site.name}",
            subtitle = if (selectedDatabase == null) {
                "Private SQLite files for this website, stored outside its public files."
            } else {
                "SQLite workbench · ${selectedDatabase}.db"
            },
            backLabel = if (selectedDatabase == null) "Website" else "Databases",
            onBack = {
                if (selectedDatabase != null) {
                    selectedDatabase = null
                    queryResult = null
                    error = ""
                } else onBack()
            },
            actions = {
                LocalStatusPill("SQLITE", LocalGreen, Color(0xFF173D43))
            },
        )

        if (error.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = if (error == "Database exported successfully.") Color(0xFFE9F7F0) else Color(0xFFFFF1F0)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    error,
                    Modifier.padding(14.dp),
                    color = if (error == "Database exported successfully.") LocalGreen else Color(0xFFB42318),
                    fontSize = 12.sp,
                )
            }
        }

        if (selectedDatabase == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { newDatabaseName = ""; showCreateDialog = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                ) { Text("New database") }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream", "*/*")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Import SQLite") }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (databases.isEmpty() && !busy) item {
                    LocalPremiumCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No local databases yet", color = LocalNavy, fontWeight = FontWeight.Bold)
                            Text("Create a SQLite database or import a .db file to start.", color = LocalMuted, fontSize = 12.sp)
                        }
                    }
                }
                items(databases, key = { it.name }) { database ->
                    LocalPremiumCard {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(database.name, color = LocalNavy, fontWeight = FontWeight.Bold)
                                    Text("${database.name}.db · ${formatDatabaseSize(database.sizeBytes)}", color = LocalMuted, fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = {
                                        error = ""
                                        busy = true
                                        scope.launch {
                                            try {
                                                openDatabase(database.name)
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Database could not be opened."
                                                selectedDatabase = null
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Open") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        pendingExport = database.name
                                        exportPicker.launch("${database.name}.db")
                                    },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("Export") }
                                TextButton(onClick = { pendingDelete = database.name }, enabled = !busy) {
                                    Text("Delete", color = Color(0xFFB42318))
                                }
                            }
                        }
                    }
                }
                item { Column(Modifier.padding(bottom = 12.dp)) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pendingExport = selectedDatabase
                            exportPicker.launch("${selectedDatabase}.db")
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Export database") }
                    TextButton(
                        onClick = { selectedDatabase = null; queryResult = null; error = "" },
                        enabled = !busy,
                    ) { Text("All databases") }
                }
                Text("Run one statement · SELECT, INSERT, UPDATE, DELETE, CREATE/DROP/ALTER TABLE", color = LocalMuted, fontSize = 11.sp)
                OutlinedTextField(
                    value = sql,
                    onValueChange = { sql = it.take(8_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SQL statement") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !busy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { selectedDatabase?.let { runQuery(it) } },
                        enabled = !busy && sql.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Run SQL") }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
                queryResult?.let { result ->
                    Text(result.message, color = LocalNavy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    if (result.columns.isNotEmpty()) {
                        Text(result.columns.joinToString("  |  "), color = LocalBlue, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    if (result.truncated || result.columnsTruncated) {
                        Text(
                            buildString {
                                append("Results are limited to ")
                                if (result.columnsTruncated) append("30 columns")
                                if (result.columnsTruncated && result.truncated) append(" and ")
                                if (result.truncated) append("100 rows")
                                append('.')
                            },
                            color = LocalMuted,
                            fontSize = 11.sp,
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(result.rows.indices.toList()) { index ->
                            val row = result.rows[index]
                            LocalPremiumCard {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    row.forEachIndexed { columnIndex, value ->
                                        Text(
                                            "${result.columns.getOrElse(columnIndex) { "Column ${columnIndex + 1}" }}: ${value.ifBlank { "(empty)" }}",
                                            color = LocalNavy,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            maxLines = 3,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create SQLite database") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stored privately on this phone; it is not exposed by the website server.", color = LocalMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = newDatabaseName,
                        onValueChange = { newDatabaseName = it.take(40) },
                        label = { Text("Database name") },
                        placeholder = { Text("catalog") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newDatabaseName.trim()
                        showCreateDialog = false
                        scope.launch {
                            busy = true
                            error = ""
                            try {
                                withContext(Dispatchers.IO) { manager.create(site.id, name) }
                                reloadDatabases()
                                openDatabase(name)
                            } catch (failure: Exception) {
                                error = failure.message ?: "Database could not be created."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && newDatabaseName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import SQLite database") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The file will be copied into private app storage. Existing databases are not overwritten.", color = LocalMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it.take(40) },
                        label = { Text("Save as") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = importName.trim()
                        pendingImport = null
                        scope.launch {
                            busy = true
                            error = ""
                            try {
                                withContext(Dispatchers.IO) {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException("Selected database could not be opened.")
                                    manager.importDatabase(site.id, name, input)
                                }
                                reloadDatabases()
                            } catch (failure: Exception) {
                                error = failure.message ?: "Database import failed."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && importName.isNotBlank(),
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete $name.db?") },
            text = { Text("This removes the private database file and cannot be undone. Export it first if you need a copy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            busy = true
                            error = ""
                            try {
                                withContext(Dispatchers.IO) { manager.delete(site.id, name) }
                                reloadDatabases()
                            } catch (failure: Exception) {
                                error = failure.message ?: "Database could not be deleted."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text("Delete", color = Color(0xFFB42318)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private const val DEFAULT_SCHEMA_QUERY = "SELECT name, type, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name <> '__anbhosting_meta' ORDER BY type, name;"

private fun suggestDatabaseName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName)
    val cleaned = base.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_', '-')
    val validStart = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "db_$cleaned"
    return validStart.take(40).ifBlank { "imported_db" }
}

private fun formatDatabaseSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
