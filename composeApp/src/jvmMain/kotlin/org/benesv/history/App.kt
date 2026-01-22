package org.benesv.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kmp_history_search.composeapp.generated.resources.Res
import kmp_history_search.composeapp.generated.resources.chrome
import kmp_history_search.composeapp.generated.resources.chromium
import kmp_history_search.composeapp.generated.resources.zen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.benesv.history.core.Log
import org.benesv.history.core.TimeUtil
import org.benesv.history.data.HistoryRepository
import org.benesv.history.data.fuzzyFilter
import org.benesv.history.model.BrowserSelection
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.painterResource
import java.awt.Cursor
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.net.URI
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()

        val repo = rememberSaveable { HistoryRepository() }
        var selection by remember { mutableStateOf<BrowserSelection>(BrowserSelection.All) }
        var query by remember { mutableStateOf(TextFieldValue("")) }
        val allItems by repo.historyFlow.collectAsState()
        var view by remember { mutableStateOf(listOf<HistoryItem>()) }
        var loading by remember { mutableStateOf(false) }
        var dbCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var selectedIndex by remember { mutableStateOf(-1) }
        var deleteFavicons by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        val focus = remember { FocusRequester() }
        var inputFocused by remember { mutableStateOf(true) }

        var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        var suggestionIndex by remember { mutableStateOf(0) }

        fun currentPrefix(): String {
            val parts = query.text.trimEnd().split(Regex("\\s+"))
            return if (parts.isNotEmpty()) parts.last() else ""
        }

        LaunchedEffect(allItems, selection, query.text) {
            Log.i("Recomputing view with query: ${query.text} and all items: ${allItems.size}")
            view = fuzzyFilter(filterBySelection(allItems, selection), query.text)
            selectedIndex = -1
        }

        LaunchedEffect(allItems) {
            if (allItems.isNotEmpty()) loading = false
        }

        LaunchedEffect(selectedIndex) {
            if (selectedIndex >= 0 && selectedIndex < view.size) {
                listState.animateScrollToItem(selectedIndex)
            }
        }

        LaunchedEffect(Unit){
            focus.requestFocus()
        }

        // Recompute suggestions when text changes
        LaunchedEffect(query.text) {
            val p = currentPrefix()
            if (p.isBlank()) {
                suggestions = emptyList()
                suggestionIndex = 0
            } else {
                val list = repo.getSuggestions(p, limit = 10)
                suggestions = list
                suggestionIndex = 0
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    // Alt+Up/Down cycles suggestions
                    if (UiHotkeys.isCycleSuggestionDown(event) && suggestions.isNotEmpty()) {
                        Log.i("Cycling suggestion down, current index: $suggestionIndex")
                        suggestionIndex = (suggestionIndex + 1) % suggestions.size
                        return@onPreviewKeyEvent true
                    }
                    if (UiHotkeys.isCycleSuggestionUp(event) && suggestions.isNotEmpty()) {
                        Log.i("Cycling suggestion up, current index: $suggestionIndex")
                        suggestionIndex = if (suggestionIndex - 1 < 0) suggestions.lastIndex else suggestionIndex - 1
                        return@onPreviewKeyEvent true
                    }

                    // Plain Up/Down navigates history list
                    if (UiHotkeys.isListDown(event)) {
                        if (view.isNotEmpty()) {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(view.size - 1)
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (UiHotkeys.isListUp(event)) {
                        if (selectedIndex > 0) {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (UiHotkeys.isActivate(event)) {
                        if (selectedIndex >= 0 && selectedIndex < view.size) {
                            val url = view[selectedIndex].url
                            scope.launch(Dispatchers.IO) { repo.saveTokensFromQuery(query.text) }
                            Desktop.getDesktop().browse(URI(url))
                        }
                        return@onPreviewKeyEvent true
                    }

                    false
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = currentPrefix()
                val selectedSuggestion = suggestions.getOrNull(suggestionIndex)?.takeIf { it.startsWith(prefix) }
                val suffix = selectedSuggestion?.drop(prefix.length).orEmpty()

                Box(Modifier.weight(1f)) {
                    // Switch to BasicTextField within Material3 DecorationBox for pixel-perfect overlay
                    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                    var cursorX by remember { mutableStateOf(0f) }

                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { inputFocused = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                                when {
                                    // Tab: accept keyword suggestion (completes the first word from start-of-line)
                                    UiHotkeys.isAcceptSuggestion(e) -> {
                                        if (suffix.isNotEmpty()) {
                                            val newText = query.text + suffix
                                            query = query.copy(text = newText, selection = TextRange(newText.length))
                                            suggestions = emptyList()
                                            true
                                        } else false
                                    }
                                    // Alt + Left: move to previous word start
                                    UiHotkeys.isPrevWord(e) -> {
                                        query = UiHotkeys.movePrevWord(query)
                                        true
                                    }
                                    // Alt + Right: move to next word end
                                    UiHotkeys.isNextWord(e) -> {
                                        query = UiHotkeys.moveNextWord(query)
                                        true
                                    }
                                    // Ctrl + W: delete previous word
                                    UiHotkeys.isDeletePrevWord(e) -> {
                                        query = UiHotkeys.deletePrevWord(query)
                                        true
                                    }
                                    // Shift + Option(Alt) + K: delete line (clear input)
                                    UiHotkeys.isClearLine(e) -> {
                                        query = UiHotkeys.clearLine()
                                        suggestions = emptyList()
                                        true
                                    }

                                    else -> false
                                }
                            },
                        singleLine = true,
                        textStyle = textStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { layout ->
                            // Cursor X position for the end of text in pixels
                            val caret = query.selection.end.coerceIn(0, query.text.length)
                            cursorX = layout.getCursorRect(caret).left
                        },
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = query.text,
                                innerTextField = {
                                    Box(Modifier.fillMaxWidth()) {
                                        innerTextField()
                                        if (suffix.isNotEmpty() && query.text.isNotBlank()) {
                                            // Render suffix at exact measured x of typed text
                                            Text(
                                                text = suffix,
                                                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip,
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    // Pixel-precise positioning without DP rounding via translationX in px
                                                    .graphicsLayer { translationX = cursorX }
                                            )
                                        }
                                    }
                                },
                                enabled = true,
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = remember { MutableInteractionSource() },
                                isError = false,
                                label = { Text("Search browser history…") },
                                placeholder = null,
                                leadingIcon = null,
                                trailingIcon = null,
                                supportingText = null,
                                prefix = null,
                                suffix = null,
                                colors = OutlinedTextFieldDefaults.colors()
                            )
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = selection is BrowserSelection.All, onClick = { selection = BrowserSelection.All }, label = { Text("All") })
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = selection is BrowserSelection.Single && (selection as BrowserSelection.Single).browser == BrowserType.Chrome,
                    onClick = { selection = BrowserSelection.Single(BrowserType.Chrome) },
                    label = { Text("Chrome") })
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = selection is BrowserSelection.Single && (selection as BrowserSelection.Single).browser == BrowserType.Zen,
                    onClick = { selection = BrowserSelection.Single(BrowserType.Zen) },
                    label = { Text("Zen") })
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = selection is BrowserSelection.Single && (selection as BrowserSelection.Single).browser == BrowserType.Thorium,
                    onClick = { selection = BrowserSelection.Single(BrowserType.Thorium) },
                    label = { Text("Thorium") })
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = deleteFavicons,
                    onClick = { deleteFavicons = !deleteFavicons },
                    label = { Text(if (deleteFavicons) "Delete Favicons" else "Keep Favicons") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        loading = true
                        try {
                            repo.refresh(selection, deleteFavicons)
                        } finally {
                            loading = false
                        }
                    }
                }) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        dbCounts = repo.validateDatabase()
                    }
                }) { Text("Validate DB") }
            }
            Spacer(Modifier.height(8.dp))

            val isLoadingMore by repo.isLoadingMore.collectAsState()
            val isInitialLoading = allItems.isEmpty()

            if (loading || isInitialLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Simple DB stats so user can verify it's filled correctly
            dbCounts?.let { (h, f) ->
                Spacer(Modifier.height(6.dp))
                Text("Database: history=$h, favicons=$f", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }

            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(view) { idx, item ->
                    HistoryRow(
                        item = item,
                        isSelected = idx == selectedIndex,
                        onRowClick = {
                            selectedIndex = idx
                            scope.launch(Dispatchers.IO) { repo.saveTokensFromQuery(query.text) }
                            Desktop.getDesktop().browse(URI(item.url))
                        }
                    )
                    if (idx < view.lastIndex) HorizontalDivider()

                    // Load more when reaching near the end
                    if (idx == view.size - 10 && !isLoadingMore) {
                        LaunchedEffect(Unit) {
                            repo.loadMore()
                        }
                    }
                }

                if (isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinearProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(InternalResourceApi::class)
@Composable
private fun HistoryRow(
    item: HistoryItem,
    isSelected: Boolean,
    onRowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onRowClick)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            FaviconImage(
                imageData = item.favicon?.imageData,
                modifier = Modifier.size(24.dp),
                fallback = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "url",
                        modifier = Modifier.size(24.dp)
                    )
                }
            )
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { item.url },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                item.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(8.dp))

        // Metadata section
        Column(horizontalAlignment = Alignment.End) {
            Text(
                TimeUtil.formatMillis(item.lastVisit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${item.visitCount} visits",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(16.dp))

        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(
                    when (item.browser) {
                        BrowserType.Chrome -> Res.drawable.chrome
                        BrowserType.Zen -> Res.drawable.zen
                        BrowserType.Thorium -> Res.drawable.chromium
                    }
                ),
                contentDescription = when (item.browser) {
                    BrowserType.Chrome -> "Chrome icon"
                    BrowserType.Zen -> "Zen icon"
                    BrowserType.Thorium -> "Thorium icon"
                },
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Composable to display favicon from data URI or URL.
 */
@Composable
private fun FaviconImage(
    imageData: ByteArray?,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
) {
    val imageBitmap = remember(imageData) {
        imageData?.let { bytes ->
            // Try Skia first (supports PNG/WebP/ICO and more), then fall back to ImageIO
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrElse {
                runCatching {
                    ImageIO.read(ByteArrayInputStream(bytes)).toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }
    if (imageBitmap != null) {
        Image(
            painter = remember(imageBitmap) { BitmapPainter(imageBitmap) },
            contentDescription = "Favicon",
            modifier = modifier
        )
    } else {
        // Fallback: show provided composable (e.g., browser icon) or an empty box
        if (fallback != null) fallback() else Box(modifier.fillMaxSize())
    }
}

private fun filterBySelection(items: List<HistoryItem>, sel: BrowserSelection): List<HistoryItem> = when (sel) {
    is BrowserSelection.All -> items
    is BrowserSelection.Single -> items.filter { it.browser == sel.browser }
}
