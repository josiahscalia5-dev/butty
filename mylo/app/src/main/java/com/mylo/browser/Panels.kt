package com.mylo.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import java.text.DateFormat
import java.util.Date

private val PanelNavy = Color(0xFF101B35)
private val PanelCard = Color(0xFF192644)
private val PanelText = Color(0xFFF5F1FF)
private val PanelMuted = Color(0xFFAFB9D3)
private val PanelAccent = Color(0xFFD0BEFF)

/** Native, working destinations for the home screen's shortcuts and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyloPanel(
    panel: String,
    store: BrowserStore,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onOpenShield: () -> Unit = {},
) {
    var currentPanel by remember(panel) { mutableStateOf(panel) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val panelScroll = rememberScrollState()
    val title = when (currentPanel) {
        "bookmarks" -> "Your bookmarks"
        "history" -> "Browsing history"
        "tabs" -> "Your tabs"
        "settings" -> "Search engine"
        "tools" -> "Browser tools"
        "mylo" -> "Hello, from Mylo"
        else -> "Mylo"
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PanelNavy,
        contentColor = PanelText,
        scrimColor = Color(0xAA030817),
    ) {
        Column(Modifier.fillMaxWidth().then(
            if (currentPanel in listOf("settings", "tools", "mylo")) Modifier.verticalScroll(panelScroll) else Modifier
        ).padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close $title", tint = PanelMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (currentPanel) {
                "bookmarks" -> {
                    PanelDescription("Keep a little of what you love.")
                    PanelPrimaryButton("Add bookmark", Icons.Outlined.Add) { showAddBookmark = true }
                    if (store.bookmarks.isEmpty()) {
                        PanelEmpty(Icons.Outlined.BookmarkBorder, "Your next favorite belongs here", "Save a page while browsing, or add a web address above.")
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(store.bookmarks, key = { it.url }) { bookmark ->
                                PanelLinkRow(
                                    icon = Icons.Outlined.BookmarkBorder,
                                    title = bookmark.title.ifBlank { bookmark.url },
                                    subtitle = bookmark.url,
                                    onClick = { onClose(); onOpenUrl(bookmark.url) },
                                    trailing = {
                                        IconButton(onClick = { store.removeBookmark(bookmark.url) }) {
                                            Icon(Icons.Outlined.DeleteOutline, "Remove ${bookmark.title.ifBlank { bookmark.url }}", tint = PanelMuted)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                "history" -> {
                    PanelDescription("Pick up where your curiosity left off.")
                    if (store.history.isEmpty()) {
                        PanelEmpty(Icons.Outlined.History, "A fresh start", "Pages you visit in regular tabs appear here. Private tabs do not save Mylo history.")
                    } else {
                        TextButton(onClick = { showClearHistory = true }) {
                            Icon(Icons.Outlined.DeleteOutline, null, tint = PanelAccent)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear browsing history", color = PanelAccent)
                        }
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(store.history) { entry ->
                                PanelLinkRow(
                                    Icons.Outlined.History,
                                    entry.title.ifBlank { entry.url },
                                    "${displayHost(entry.url)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.visitedAt))}",
                                    onClick = { onClose(); onOpenUrl(entry.url) },
                                )
                            }
                        }
                    }
                }
                "tabs" -> {
                    PanelDescription("A place for every new discovery.")
                    PanelPrimaryButton("New tab", Icons.Outlined.Add) { onClose(); onNewTab() }
                    if (store.tabs.isEmpty()) {
                        PanelEmpty(Icons.Outlined.Tab, "Room to explore", "Open a new tab to start browsing.")
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(store.tabs, key = { it.id }) { tab ->
                                PanelLinkRow(
                                    icon = if (tab.privateMode) Icons.Outlined.Shield else Icons.Outlined.Tab,
                                    title = tab.title.ifBlank { "New tab" },
                                    subtitle = if (tab.privateMode) "Private · ${tab.url.ifBlank { "Ready to explore" }}" else tab.url.ifBlank { "Ready to explore" },
                                    onClick = { onClose(); onSelectTab(tab) },
                                    trailing = {
                                        IconButton(onClick = { store.closeTab(tab.id) }) {
                                            Icon(Icons.Outlined.Close, "Close ${tab.title.ifBlank { "tab" }}", tint = PanelMuted)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                "settings" -> SearchProviderChoices(store)
                "tools" -> {
                    PanelDescription("Simple controls for a brighter browse.")
                    PanelLinkRow(Icons.Outlined.ManageSearch, "Search engine", store.provider.displayName, onClick = { currentPanel = "settings" })
                    Spacer(Modifier.height(8.dp))
                    PanelLinkRow(Icons.Outlined.Download, "Downloads", "Open your Android downloads", onClick = {
                        launchPanelIntent(context, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                    })
                    Spacer(Modifier.height(8.dp))
                    PanelLinkRow(Icons.Outlined.Shield, "Mylo Shield VPN", "Connect, choose a location and set up the kill switch", onClick = { onClose(); onOpenShield() })
                    Spacer(Modifier.height(8.dp))
                    PanelLinkRow(Icons.Outlined.Settings, "App settings", "Permissions, storage and notifications", onClick = {
                        launchPanelIntent(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    })
                }
                "mylo" -> {
                    Surface(color = PanelCard, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Mylo", color = PanelAccent, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                            Text("A brighter web awaits.", color = PanelText, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                            Text("A little more curiosity. A little more wonder. Your bookmarks, search preferences and regular browsing history stay on this device.", color = PanelMuted, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(color = PanelMuted.copy(alpha = .15f), modifier = Modifier.padding(vertical = 6.dp))
                            Text("${store.bookmarks.size} bookmarks  ·  ${store.tabs.size} open tabs", color = PanelAccent, fontSize = 13.sp)
                            Text("Mylo for Android · 1.0", color = PanelMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PanelLinkRow(Icons.Outlined.ManageSearch, "Make yourself at home", "Choose your search provider", onClick = { currentPanel = "settings" })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddBookmark) {
        AddBookmarkDialog(
            onDismiss = { showAddBookmark = false },
            onAdd = { url, title ->
                store.addBookmark(url, title)
                showAddBookmark = false
            },
        )
    }
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            containerColor = PanelNavy,
            titleContentColor = PanelText,
            textContentColor = PanelMuted,
            title = { Text("Clear browsing history?") },
            text = { Text("This removes saved page visits from Mylo on this device. Your bookmarks will stay.") },
            confirmButton = {
                TextButton(onClick = { store.clearHistory(); showClearHistory = false }) { Text("Clear history", color = PanelAccent) }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("Keep history", color = PanelMuted) } },
        )
    }
}

@Composable
private fun SearchProviderChoices(store: BrowserStore) {
    // The only place the search provider is chosen; the Home search box always uses this saved choice.
    PanelDescription("Choose your default search provider. Web addresses open directly; everything you type in the Home search box searches with it.")
    Column(Modifier.testTag("search-provider-settings")) { SearchProvider.entries.forEach { provider ->
        Surface(
            color = if (store.provider == provider) Color(0xFF2A2850) else PanelCard,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { store.setProvider(provider) },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = store.provider == provider,
                    onClick = { store.setProvider(provider) },
                    colors = RadioButtonDefaults.colors(selectedColor = PanelAccent, unselectedColor = PanelMuted),
                )
                Text(provider.displayName, Modifier.padding(start = 8.dp), color = PanelText, fontSize = 17.sp)
            }
        }
    } }
    Text("Your choice is saved on this device and kept after Mylo restarts.", Modifier.padding(top = 8.dp), color = PanelMuted, fontSize = 13.sp)
}

@Composable
private fun PanelDescription(text: String) {
    Text(text, Modifier.padding(bottom = 18.dp), color = PanelMuted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PanelPrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelAccent, contentColor = PanelNavy),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.padding(vertical = 6.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PanelLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(color = PanelCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onClick).padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = PanelAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = PanelText, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = PanelMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun PanelEmpty(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).background(PanelCard, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = PanelAccent)
        }
        Text(title, color = PanelText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(body, color = PanelMuted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun AddBookmarkDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var attemptedSave by remember { mutableStateOf(false) }
    val validUrl = bookmarkUrl(address)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelNavy,
        titleContentColor = PanelText,
        textContentColor = PanelMuted,
        title = { Text("Save a favorite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; attemptedSave = false },
                    label = { Text("Web address") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = attemptedSave && validUrl == null,
                    supportingText = if (attemptedSave && validUrl == null) ({ Text("Enter a valid HTTP or HTTPS web address.") }) else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                attemptedSave = true
                if (validUrl != null) onAdd(validUrl, title.trim().ifBlank { displayHost(validUrl) })
            }) { Text("Save bookmark", color = PanelAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PanelMuted) } },
    )
}

private fun bookmarkUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching {
        val uri = URI(candidate)
        if (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65535) null else uri.toASCIIString()
    }.getOrNull()
}

private fun displayHost(url: String): String = runCatching { Uri.parse(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

private fun launchPanelIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "This Android device does not have an app for that action.", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Android could not open this setting.", Toast.LENGTH_LONG).show()
    }
}
