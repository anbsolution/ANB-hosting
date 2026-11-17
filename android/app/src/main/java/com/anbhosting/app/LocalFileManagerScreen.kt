package com.anbhosting.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalFileManagerScreen(
    manager: LocalFileManager,
    site: LocalSite,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentDirectory by remember(site.id) { mutableStateOf("") }
    var entries by remember(site.id) { mutableStateOf(emptyList<LocalFileEntry>()) }
    var editingPath by remember(site.id) { mutableStateOf<String?>(null) }
    var editorContents by remember(site.id) { mutableStateOf("") }
    var savedEditorContents by remember(site.id) { mutableStateOf("") }
    var busy by remember(site.id) { mutableStateOf(false) }
    var error by remember(site.id) { mutableStateOf("") }
    var selectedEntry by remember(site.id) { mutableStateOf<LocalFileEntry?>(null) }
    var dialogMode by remember(site.id) { mutableStateOf("") }
    var dialogName by remember(site.id) { mutableStateOf("") }
    var menuPath by remember(site.id) { mutableStateOf<String?>(null) }
    var pendingDownload by remember(site.id) { mutableStateOf<String?>(null) }
    var showRestoreWarning by remember(site.id) { mutableStateOf(false) }
    var showDiscardWarning by remember(site.id) { mutableStateOf(false) }

    fun navigateBack() {
        when {
            editingPath != null && editorContents != savedEditorContents -> showDiscardWarning = true
            editingPath != null -> editingPath = null
            currentDirectory.isNotBlank() -> currentDirectory = currentDirectory.substringBeforeLast('/', "")
            else -> onBack()
        }
    }

    BackHandler(onBack = ::navigateBack)

    fun refresh() {
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { manager.list(site.id, currentDirectory) }
                error = ""
            } catch (failure: Exception) {
                error = failure.message ?: "Files could not be loaded."
            }
        }
    }

    fun operate(action: () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            try {
                withContext(Dispatchers.IO) { action() }
                refresh()
            } catch (failure: Exception) {
                error = failure.message ?: "The file operation failed."
            } finally {
                busy = false
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val uploadDirectory = currentDirectory
            operate {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.takeIf(String::isNotBlank)
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    ?: "upload.bin"
                val path = listOf(uploadDirectory, name).filter(String::isNotBlank).joinToString("/")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file could not be opened.")
                manager.importFile(site.id, path, input)
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            currentDirectory = ""
            editingPath = null
            operate {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The backup could not be opened.")
                manager.restoreBackup(site.id, input)
            }
        }
    }

    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val path = pendingDownload
        pendingDownload = null
        if (uri != null && path != null) operate {
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("The destination could not be opened for writing.")
            manager.exportFile(site.id, path, output)
        }
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) operate {
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("The backup destination could not be opened.")
            manager.createBackup(site.id, output)
        }
    }

    LaunchedEffect(site.id, currentDirectory) { refresh() }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = LocalBlue)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F6FB)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(LocalNavy).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = ::navigateBack,
                        ) {
                            Text(if (editingPath != null || currentDirectory.isNotBlank()) "‹ Back" else "‹ Websites", color = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("LOCAL FILES", color = Color(0xFFB9C7E7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(site.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        editingPath ?: currentDirectory.ifBlank { "Website root" },
                        color = Color(0xFFB9C7E7),
                        fontSize = 12.sp,
                    )
                }

                if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp))
                if (error.isNotBlank()) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F0)),
                    ) {
                        Text(error, Modifier.padding(14.dp), color = Color(0xFFB42318), fontSize = 12.sp)
                    }
                }

                if (editingPath != null) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("HTML · CSS · JavaScript · JSON · text", color = LocalMuted, fontSize = 11.sp)
                        OutlinedTextField(
                            value = editorContents,
                            onValueChange = { editorContents = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = { Text("File contents") },
                            minLines = 12,
                        )
                        Button(
                            onClick = {
                                val path = editingPath ?: return@Button
                                val contents = editorContents
                                scope.launch {
                                    busy = true
                                    error = ""
                                    try {
                                        withContext(Dispatchers.IO) { manager.writeText(site.id, path, contents) }
                                        savedEditorContents = contents
                                        refresh()
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "File could not be saved."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save file") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Upload") }
                        OutlinedButton(onClick = { dialogName = ""; dialogMode = "new-file" }, enabled = !busy) { Text("New file") }
                        OutlinedButton(onClick = { dialogName = ""; dialogMode = "new-folder" }, enabled = !busy) { Text("Folder") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextButton(onClick = { backupPicker.launch("ANB-${site.slug}-backup.zip") }, enabled = !busy) {
                            Text("Backup")
                        }
                        TextButton(onClick = { showRestoreWarning = true }, enabled = !busy) { Text("Restore backup") }
                    }

                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (entries.isEmpty()) item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                    Text("This folder is empty", color = LocalNavy, fontWeight = FontWeight.Bold)
                                    Text("Upload a website file or create a text file to begin.", color = LocalMuted, fontSize = 12.sp)
                                }
                            }
                        }
                        items(entries, key = { it.relativePath }) { entry ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            color = LocalNavy,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                        )
                                        Text(
                                            if (entry.isDirectory) "Folder" else formatFileSize(entry.size),
                                            color = LocalMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    if (entry.isDirectory) {
                                        TextButton(onClick = { currentDirectory = entry.relativePath }) { Text("Open") }
                                    } else if (isEditorFile(entry.name)) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try {
                                                        editorContents = withContext(Dispatchers.IO) {
                                                            manager.readText(site.id, entry.relativePath)
                                                        }
                                                        savedEditorContents = editorContents
                                                        editingPath = entry.relativePath
                                                        error = ""
                                                    } catch (failure: Exception) {
                                                        error = failure.message ?: "File could not be opened."
                                                    } finally {
                                                        busy = false
                                                    }
                                                }
                                            },
                                            enabled = !busy,
                                        ) { Text("Edit") }
                                    }
                                    TextButton(onClick = { menuPath = entry.relativePath }, enabled = !busy) { Text("⋮") }
                                    DropdownMenu(
                                        expanded = menuPath == entry.relativePath,
                                        onDismissRequest = { menuPath = null },
                                    ) {
                                        if (!entry.isDirectory) DropdownMenuItem(
                                            text = { Text("Download") },
                                            onClick = {
                                                menuPath = null
                                                pendingDownload = entry.relativePath
                                                downloadPicker.launch(entry.name)
                                            },
                                        )
                                        if (!entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)) {
                                            DropdownMenuItem(
                                                text = { Text("Extract ZIP") },
                                                onClick = {
                                                    menuPath = null
                                                    val archiveName = entry.name.substringBeforeLast('.')
                                                    val parent = entry.relativePath.substringBeforeLast('/', "")
                                                    val target = listOf(parent, archiveName).filter(String::isNotBlank).joinToString("/")
                                                    operate { manager.extractZip(site.id, entry.relativePath, target) }
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                selectedEntry = entry
                                                dialogName = entry.name
                                                dialogMode = "rename"
                                                menuPath = null
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = Color(0xFFB42318)) },
                                            onClick = {
                                                selectedEntry = entry
                                                dialogMode = "delete"
                                                menuPath = null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(14.dp)) }
                    }
                }
            }
        }
    }

    if (dialogMode == "new-file" || dialogMode == "new-folder" || dialogMode == "rename") {
        val title = when (dialogMode) {
            "new-file" -> "Create code file"
            "new-folder" -> "Create folder"
            else -> "Rename item"
        }
        AlertDialog(
            onDismissRequest = { dialogMode = "" },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = dialogName,
                    onValueChange = { if (it.length <= 120) dialogName = it },
                    label = { Text(if (dialogMode == "new-folder") "Folder name" else "File name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mode = dialogMode
                        val name = dialogName.trim()
                        val entry = selectedEntry
                        dialogMode = ""
                        when (mode) {
                            "new-file" -> {
                                val path = listOf(currentDirectory, name).filter(String::isNotBlank).joinToString("/")
                                operate { manager.createTextFile(site.id, path) }
                            }
                            "new-folder" -> {
                                val path = listOf(currentDirectory, name).filter(String::isNotBlank).joinToString("/")
                                operate { manager.createDirectory(site.id, path) }
                            }
                            "rename" -> if (entry != null) operate { manager.rename(site.id, entry.relativePath, name) }
                        }
                    },
                    enabled = !busy && dialogName.trim().isNotBlank(),
                ) { Text(if (dialogMode == "rename") "Rename" else "Create") }
            },
            dismissButton = { TextButton(onClick = { dialogMode = "" }) { Text("Cancel") } },
        )
    }

    if (dialogMode == "delete") {
        val entry = selectedEntry
        AlertDialog(
            onDismissRequest = { dialogMode = "" },
            title = { Text("Delete ${entry?.name.orEmpty()}?") },
            text = { Text("This permanently removes the selected item from this website.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogMode = ""
                        entry?.let { item -> operate { manager.delete(site.id, item.relativePath) } }
                    },
                    enabled = !busy,
                ) { Text("Delete", color = Color(0xFFB42318)) }
            },
            dismissButton = { TextButton(onClick = { dialogMode = "" }) { Text("Cancel") } },
        )
    }

    if (showRestoreWarning) {
        AlertDialog(
            onDismissRequest = { showRestoreWarning = false },
            title = { Text("Restore website backup?") },
            text = { Text("Restore replaces every current file in this website. Choose a ZIP backup to continue.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreWarning = false
                        restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                ) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { showRestoreWarning = false }) { Text("Cancel") } },
        )
    }

    if (showDiscardWarning) {
        AlertDialog(
            onDismissRequest = { showDiscardWarning = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your latest edits have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardWarning = false
                        editorContents = savedEditorContents
                        editingPath = null
                    },
                ) { Text("Discard changes") }
            },
            dismissButton = { TextButton(onClick = { showDiscardWarning = false }) { Text("Keep editing") } },
        )
    }
}

private fun isEditorFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "css", "js", "mjs", "json", "txt", "xml", "svg", "md", "yml", "yaml")

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
