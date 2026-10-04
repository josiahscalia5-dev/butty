package com.mylo.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Input only. Every submission is resolved and loaded by the normal browser. */
@Composable
fun SearchInputScreen(
    query: String,
    onQuery: (String) -> Unit,
    provider: SearchProvider,
    onProvider: (SearchProvider) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var choosingProvider by remember { mutableStateOf(false) }

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
            Box(Modifier.padding(start = 48.dp)) {
                TextButton(
                    onClick = { choosingProvider = true; keyboard?.hide() },
                    modifier = Modifier.semantics { contentDescription = "Search provider: ${provider.displayName}" },
                ) {
                    Text("Search with ${provider.displayName}")
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp))
                }
                DropdownMenu(
                    expanded = choosingProvider,
                    onDismissRequest = { choosingProvider = false },
                    modifier = Modifier.testTag("search-provider-picker"),
                ) {
                    SearchProvider.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.displayName) },
                            onClick = { onProvider(choice); choosingProvider = false },
                            trailingIcon = {
                                if (choice == provider) Icon(Icons.Rounded.Check, "Selected")
                            },
                        )
                    }
                }
            }
        }
    }
}
