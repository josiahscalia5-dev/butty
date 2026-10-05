package com.mylo.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Native browser chrome only: the provider's webpage is laid out below this row. */
@Composable
internal fun BrowserToolbar(
    address: String, editing: Boolean, onAddress: (String) -> Unit, onFocus: (Boolean) -> Unit,
    onSubmit: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, canForward: Boolean,
    onReload: () -> Unit, onBookmark: () -> Unit,
) {
    val accent = Color(0xFFD5C9FF)
    Row(Modifier.fillMaxWidth().testTag("browser-toolbar").padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BrowserAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        BrowserAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", onForward, canForward)
        Surface(Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(18.dp),
            color = Color(0xFF172443),
            border = BorderStroke(1.dp, if (editing) accent.copy(alpha = .75f) else Color(0xFF343D61)),
            shadowElevation = 1.dp) {
            BasicTextField(
                value = address, onValueChange = onAddress, singleLine = true,
                modifier = Modifier.fillMaxSize().testTag("browser-address")
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = "Browser address" }
                    .padding(horizontal = 12.dp).wrapContentHeight(),
                textStyle = TextStyle(fontSize = 14.sp, color = if (editing) Color(0xFFF4F0FF) else Color.Transparent),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { editor ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        // Keep the real editor mounted for accessibility and native
                        // selection/scrolling; ellipsize only the unfocused display.
                        editor()
                        if (!editing) Text(address, color = Color(0xFFE9E5FA), fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        }
        BrowserAction(Icons.Rounded.Refresh, "Reload", onReload)
        BrowserAction(Icons.Rounded.BookmarkAdd, "Bookmark this page", onBookmark)
    }
}

@Composable
private fun BrowserAction(icon: ImageVector, label: String, action: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = Color(0xFFD5C9FF), disabledContentColor = Color(0xFF64718D).copy(alpha = .55f),
        )) {
        Icon(icon, label, Modifier.size(22.dp))
    }
}
