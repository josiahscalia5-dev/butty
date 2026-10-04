package com.mylo.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SearchAccent = Color(0xFFCEC5FF)

/** Input only. Every submission is resolved and loaded by the normal browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchInputScreen(
    query: String,
    onQuery: (String) -> Unit,
    provider: SearchProvider,
    onProvider: (SearchProvider) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    defaultProvider: SearchProvider = provider,
    onSetDefault: (SearchProvider) -> Unit = {},
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var choosingProvider by remember { mutableStateOf(false) }
    var draftProvider by remember { mutableStateOf(provider) }

    LaunchedEffect(choosingProvider) {
        if (!choosingProvider) {
            requester.requestFocus()
            // Wait until the input connection has followed Compose focus.
            withFrameNanos { }
            keyboard?.show()
        }
    }
    BackHandler { if (choosingProvider) choosingProvider = false else onClose() }

    Surface(color = Night, modifier = Modifier.fillMaxSize().testTag("search-input-mode")) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f).focusRequester(requester).testTag("search-input")
                        .semantics { contentDescription = "Search or enter address" },
                    placeholder = { Text("Search or enter address", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Rounded.Close, "Clear search")
                        }
                    },
                )
                IconButton(onClick = onSubmit, enabled = query.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Submit search")
                }
            }
            TextButton(
                onClick = {
                    draftProvider = provider
                    choosingProvider = true
                    keyboard?.hide()
                },
                modifier = Modifier.padding(start = 48.dp)
                    .semantics { contentDescription = "Search provider: ${provider.displayName}" },
            ) {
                Text("Search with ${provider.displayName}", color = SearchAccent)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp), tint = SearchAccent)
            }
        }
    }

    if (choosingProvider) {
        // Material's sheet transition observes Android's animator duration scale,
        // including disabled animations. No independent animation clock is used.
        ModalBottomSheet(
            onDismissRequest = { choosingProvider = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF101B35),
            contentColor = Color(0xFFF5F1FF),
            scrimColor = Color(0xAA030817),
            modifier = Modifier.testTag("search-provider-picker"),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 660.dp)
                .navigationBarsPadding().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Search your way", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("Choose for this search, or save a default.",
                            color = Color(0xFFAFB9D3), style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { choosingProvider = false }) {
                        Icon(Icons.Rounded.Close, "Close provider selector", tint = Color(0xFFAFB9D3))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keep the visual order intentional without changing stored enum names.
                    listOf(SearchProvider.GOOGLE, SearchProvider.BRAVE, SearchProvider.DUCKDUCKGO,
                        SearchProvider.BING, SearchProvider.YAHOO, SearchProvider.STARTPAGE).forEach { choice ->
                        val selected = choice == draftProvider
                        Surface(color = if (selected) Color(0xFF29345B) else Color(0xFF192644),
                            shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.fillMaxWidth()
                                .selectable(selected = selected, role = Role.RadioButton,
                                    onClick = { draftProvider = choice })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                ProviderMonogram(choice)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(choice.displayName, fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyLarge)
                                    if (choice == defaultProvider) Text("Default",
                                        color = SearchAccent, style = MaterialTheme.typography.labelMedium)
                                }
                                RadioButton(selected = selected, onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = SearchAccent,
                                        unselectedColor = Color(0xFF8794B5)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onProvider(draftProvider); choosingProvider = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SearchAccent, contentColor = Night),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Use for this search", fontWeight = FontWeight.SemiBold) }
                TextButton(
                    onClick = {
                        onSetDefault(draftProvider)
                        onProvider(draftProvider)
                        choosingProvider = false
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Set as default", color = SearchAccent) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Lightweight text marks identify providers without fetching assets or altering their pages. */
@Composable
private fun ProviderMonogram(provider: SearchProvider) {
    val (mark, tint) = when (provider) {
        SearchProvider.GOOGLE -> "G" to Color(0xFF8AB4F8)
        SearchProvider.BRAVE -> "Br" to Color(0xFFFFA477)
        SearchProvider.DUCKDUCKGO -> "D" to Color(0xFFFFAF96)
        SearchProvider.BING -> "b" to Color(0xFF7DDBD2)
        SearchProvider.YAHOO -> "Y!" to Color(0xFFD2A2FF)
        SearchProvider.STARTPAGE -> "S" to Color(0xFFB7BDFF)
    }
    Box(Modifier.size(40.dp).background(tint.copy(alpha = .13f), CircleShape),
        contentAlignment = Alignment.Center) {
        Text(mark, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
